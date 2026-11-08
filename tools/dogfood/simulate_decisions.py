#!/usr/bin/env python3
"""実機で撮った写真を使って、アプリの「保存する・しない」の判定を最初から最後まで再現し、採点する。

写真 1 枚を「前の画像」にして、そこから場面ごとの「次の画像」（人が動く・何も動かない・照明が一部だけ変わる・
手ぶれ・すぐ近くを覆う など）を作り、アプリと同じ判定で保存するかを、場面ごとの期待と比べる。
判定は、80×60 の比較用画像・同梱の人の検出モデル（MediaPipe Tasks）・追跡（1 対 1 の対応づけ）・
「変化が人に重なった／人の位置が動いた」・「すぐ近くを覆った」の決まりまで含む。

使い方（写真はリポジトリに入れない）:
    python3 -m venv venv && venv/bin/pip install mediapipe numpy pillow
    sudo apt-get install -y libegl1 libgles2      # MediaPipe が使う
    venv/bin/python tools/dogfood/simulate_decisions.py [--repeat 3] photo1.jpg photo2.jpg ...

ざらつき（雑音）の乱数で 1 回ごとに結果が少し変わるので、--repeat で乱数を変えて何回か採点し、合計する。

「これまで」はこの変更より前の判定、「今」は今のアプリの判定（IntervalCaptureService.judgeMotion）。
限り: 人の動きは写真の人の部分を切り取ってずらして作る。手ぶれは画像を横にずらすだけ。
変化した所を切り出して高い解像度で探す処理（SAHI）は再現しない。
"""

from __future__ import annotations

import os
import sys

import mediapipe as mp
import numpy as np
from mediapipe.tasks import python as mpt
from mediapipe.tasks.python import vision
from PIL import Image, ImageOps

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets")
GW, GH = 80, 60
# 撮り方: (変化の大きさ, 変化の広さ)
PRESETS = {"おすすめ": (28, 0.010), "外・遠く": (28, 0.003), "へや・近く": (32, 0.020)}
RNG = np.random.default_rng(1)


# ---- 人の検出（TargetDetector と同じ設定。Pixel 10 Pro XL 向け） ----

def _make_detectors():
    labels = ["person", "bicycle", "car", "motorcycle", "bus", "truck"]
    obj = vision.ObjectDetector.create_from_options(vision.ObjectDetectorOptions(
        base_options=mpt.BaseOptions(model_asset_path=os.path.join(ASSETS, "efficientdet_lite2.tflite")),
        running_mode=vision.RunningMode.IMAGE, score_threshold=0.2, max_results=20, category_allowlist=labels))
    pose = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
        base_options=mpt.BaseOptions(model_asset_path=os.path.join(ASSETS, "pose_landmarker_full.task")),
        running_mode=vision.RunningMode.IMAGE, num_poses=5, min_pose_detection_confidence=0.5))
    hand = vision.HandLandmarker.create_from_options(vision.HandLandmarkerOptions(
        base_options=mpt.BaseOptions(model_asset_path=os.path.join(ASSETS, "hand_landmarker.task")),
        running_mode=vision.RunningMode.IMAGE, num_hands=4, min_hand_detection_confidence=0.5))
    return obj, pose, hand


OBJ, POSE, HAND = _make_detectors()


def _box_from(points, visible, minp=2, pad=0.03):
    shown = [(x, y) for (x, y), v in zip(points, visible) if v and 0 <= x <= 1 and 0 <= y <= 1]
    if len(shown) < minp:
        return None
    xs, ys = [p[0] for p in shown], [p[1] for p in shown]
    return (max(0, min(xs) - pad), max(0, min(ys) - pad), min(1, max(xs) + pad), min(1, max(ys) + pad))


def detect(img):
    h, w, _ = img.shape
    mpimg = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(np.clip(img, 0, 255).astype(np.uint8)))
    out = []
    for det in OBJ.detect(mpimg).detections:
        c = det.categories[0]
        b = det.bounding_box
        kind = "PERSON" if c.category_name == "person" else "VEHICLE"
        out.append((kind, c.score, (b.origin_x / w, b.origin_y / h, (b.origin_x + b.width) / w, (b.origin_y + b.height) / h)))
    if any(k == "PERSON" and s >= 0.4 for k, s, _ in out):
        return out
    for pose in POSE.detect(mpimg).pose_landmarks:
        box = _box_from([(p.x, p.y) for p in pose], [(p.visibility or 1) >= 0.5 and (p.presence or 1) >= 0.5 for p in pose])
        if box:
            out.append(("BODY_PART", 1.0, box))
    for hand in HAND.detect(mpimg).hand_landmarks:
        box = _box_from([(p.x, p.y) for p in hand], [True] * len(hand))
        if box:
            out.append(("HAND", 1.0, box))
    return out


# ---- 比較用の画像（MotionFrameSampler・MotionDetector） ----

def upright(path, long=1024):
    im = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    w, h = im.size
    s = long / max(w, h)
    return np.asarray(im.resize((round(w * s), round(h * s)), Image.LANCZOS)).astype(np.float32)


def _luma(rgb):
    return (77 * rgb[..., 0] + 150 * rgb[..., 1] + 29 * rgb[..., 2]) >> 8


def grid_point(img):
    """これまで: 前もってぼかさずに 2 次補間で 80×60 を取る（Android の createScaledBitmap と同じ）。"""
    h, w, _ = img.shape
    ys = (np.arange(GH) + 0.5) * h / GH - 0.5
    xs = (np.arange(GW) + 0.5) * w / GW - 0.5
    y0 = np.clip(np.floor(ys).astype(int), 0, h - 2)
    fy = (ys - y0)[:, None]
    x0 = np.clip(np.floor(xs).astype(int), 0, w - 2)
    fx = (xs - x0)[None, :]
    chans = []
    for c in range(3):
        a, b = img[y0][:, x0, c], img[y0][:, x0 + 1, c]
        cc, d = img[y0 + 1][:, x0, c], img[y0 + 1][:, x0 + 1, c]
        chans.append(np.clip(np.round((a * (1 - fx) + b * fx) * (1 - fy) + (cc * (1 - fx) + d * fx) * fy), 0, 255).astype(int))
    return _luma(np.stack(chans, -1))


def grid_area(img):
    """今: 格子ごとに中の画素をすべて平均する（MotionDetector.gridFromArgb）。"""
    a = np.asarray(Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).resize((GW, GH), Image.BOX)).astype(int)
    return _luma(a)


def overlap(prev, cur, dx, dy):
    h, w = cur.shape
    return (prev[max(0, -dy):h + min(0, -dy), max(0, -dx):w + min(0, -dx)],
            cur[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)])


def _fit(p, c):
    """LightingChange.fit と同じ: 前回 p から今回 c を予想する a*p+b（白飛び・真っ黒は使わず、差の大きい 2 割を除いてもう一度）。"""
    ok = (p > 5) & (p < 250) & (c < 250)
    if ok.sum() < 20:
        return 1.0, float(c.mean() - p.mean())

    def ls(pp, cc):
        vp = ((pp - pp.mean()) ** 2).sum()
        if vp <= 1e-9:
            return 1.0, float(cc.mean() - pp.mean())
        a = ((pp - pp.mean()) * (cc - cc.mean())).sum() / vp
        return float(a), float(cc.mean() - a * pp.mean())
    pp, cc = p[ok], c[ok]
    a, b = ls(pp, cc)
    r = np.abs(cc - (a * pp + b))
    keep = r <= np.sort(r)[int((len(r) - 1) * 0.8)]
    return ls(pp[keep], cc[keep]) if keep.sum() >= 20 else (a, b)


def mask_for(prev, cur, px, dx=0, dy=0, block=0, light=False):
    """ずれ (dx, dy) を打ち消して比べた「変わった画素」（block>0 なら区画ごとに明るさをそろえる）。
    light=True は今の決まり（LightingChange）: 照明の変化を a*前回+b で打ち消し、区画ごとにも当てはめ直し、白飛びは比べない。"""
    h, w = cur.shape
    p, c = overlap(prev, cur, dx, dy)
    if light:
        p, c = p.astype(float), c.astype(float)
        a, b = _fit(p.ravel(), c.ravel())
        pred = np.clip(a * p + b, 0, 255)
        exp = pred.copy()
        if block:
            for y in range(0, p.shape[0], block):
                for x in range(0, p.shape[1], block):
                    sl = (slice(y, y + block), slice(x, x + block))
                    q, cc = pred[sl].ravel(), c[sl].ravel()
                    a2, b2 = _fit(q, cc) if q.std() > 4 else (1.0, float(cc.mean() - q.mean()))
                    exp[sl] = np.clip(a2 * pred[sl] + b2, 0, 255)
        diff = np.where(((p >= 250) | (pred >= 250)) & (c >= 245), 0, c - exp)
    else:
        diff = c - p - int(round((cur.sum() - prev.sum()) / cur.size))
        if block:
            diff = (c - p).astype(float)
            for y in range(0, diff.shape[0], block):
                for x in range(0, diff.shape[1], block):
                    diff[y:y + block, x:x + block] -= round(diff[y:y + block, x:x + block].mean())
    m = np.zeros((h, w), bool)
    m[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] = np.abs(diff) > px
    return m, m.sum() / c.size


def best_shift(prev, cur, limit=2):
    """FrameAlignment.estimate と同じく、重なった部分の差が一番小さいずれ（同じなら小さいずれ）。"""
    shift = int(round((cur.sum() - prev.sum()) / cur.size))
    costs = {}
    for dy in range(-limit, limit + 1):
        for dx in range(-limit, limit + 1):
            p, c = overlap(prev, cur, dx, dy)
            costs[(dx, dy)] = np.abs(c - p - shift).mean()
    return min(costs, key=lambda k: (round(costs[k], 9), abs(k[0]) + abs(k[1])))


# ---- 追跡と判定（TargetTracker・judgeMotion） ----

def _iou(a, b):
    l, t, r, bb = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, r - l) * max(0, bb - t)
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0


def _center(b):
    return ((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)


def _cells(m, box):
    h, w = m.shape
    l, t = int(box[0] * w), int(box[1] * h)
    r, b = int(np.ceil(box[2] * w)), int(np.ceil(box[3] * h))
    return m[t:b, l:r]


def share_inside(m, box):
    total = m.sum()
    return _cells(m, box).sum() / total if total else 0.0


def concentrated(m, box):
    inside = _cells(m, box)
    if inside.size == 0 or m.sum() == 0:
        return False
    outside_cells = m.size - inside.size
    d_in = inside.sum() / inside.size
    d_out = (m.sum() - inside.sum()) / outside_cells if outside_cells else 0.0
    return d_in >= 2 * d_out


def decide(prev_img, cur_img, prev_det, cur_det, preset, new_rules):
    px, area = PRESETS[preset]
    if new_rules:
        gp, gc = grid_area(prev_img), grid_area(cur_img)
        dx, dy = best_shift(gp, gc)
        min_move, close = 0.04, 0.15
    else:
        gp, gc = grid_point(prev_img), grid_point(cur_img)
        dx, dy = 0, 0
        min_move, close = 0.03, 0.30
    m, ratio = mask_for(gp, gc, px, dx, dy, light=new_rules)
    _, raw = mask_for(gp, gc, px)
    uneven, uneven_ratio = mask_for(gp, gc, px, dx, dy, block=10, light=True) if new_rules else (m, ratio)
    motion = ratio >= area

    unmatched = [(k, b) for k, s, b in prev_det if s >= 0.4]

    def similarity(a, b):
        i = _iou(a, b)
        if i >= 0.1:
            return 1 + i
        dist = np.hypot(*np.subtract(_center(a), _center(b)))
        return 1 - dist / 0.2 if dist <= 0.2 else 0.0

    seen = []
    for kind, _, box in sorted([d for d in cur_det if d[1] >= 0.4], key=lambda d: -(d[2][2] - d[2][0]) * (d[2][3] - d[2][1])):
        cands = [(t, similarity(t[1], box)) for t in unmatched if t[0] == kind]
        cands = [c for c in cands if c[1] > 0]
        match = max(cands, key=lambda c: c[1])[0] if cands else None
        if match:
            unmatched.remove(match)
        seen.append((kind, box, match))
    for kind, _, box in sorted([d for d in cur_det if d[1] < 0.4], key=lambda d: -d[1]):
        cands = [(t, _iou(t[1], box)) for t in unmatched if t[0] == kind]
        cands = [c for c in cands if c[1] >= 0.3]
        if cands:
            match = max(cands, key=lambda c: c[1])[0]
            unmatched.remove(match)
            seen.append((kind, box, match))
    for kind, box, match in seen:
        moved = np.hypot(*np.subtract(_center(match[1]), _center(box))) if match else 0.0
        if new_rules:
            overlaps = motion and concentrated(uneven, box) and share_inside(uneven, box) >= 0.1
        else:
            overlaps = motion and share_inside(m, box) >= 0.1
        if new_rules:
            # 位置が動いても、枠の中の画素が変わっていなければ数えない（ぶれた写真で枠だけが跳ぶ）。
            cells = _cells(uneven, box)
            moved_enough = moved >= min_move and cells.size > 0 and cells.sum() >= max(2, 0.01 * cells.size)
        else:
            moved_enough = moved >= min_move
        if overlaps or moved_enough:
            return True, f"動く{kind}（{'変化が重なる' if overlaps else f'位置が {moved:.3f} 動いた'}）"
    close_ratio = ratio if new_rules else raw
    if close_ratio >= close and (not new_rules or uneven_ratio >= 0.10):
        return True, "すぐ近くを覆った"
    return False, "動きなし"


# ---- 場面 ----

def _noisy(img, sigma):
    return np.clip(img + RNG.normal(0, sigma, img.shape), 0, 255) if sigma else img


def _shift(img, dx):
    out = np.roll(img, dx, axis=1)
    if dx > 0:
        out[:, :dx] = img[:, :1]
    return out


def _move_patch(img, box, dx_frac, scale=1.0, at=(0.15, 0.35)):
    h, w, _ = img.shape
    l, t, r, b = int(box[0] * w), int(box[1] * h), int(box[2] * w), int(box[3] * h)
    patch = img[t:b, l:r].copy()
    base = img.copy()
    if scale != 1.0:
        ph, pw = max(1, int(patch.shape[0] * scale)), max(1, int(patch.shape[1] * scale))
        patch = np.asarray(Image.fromarray(patch.astype(np.uint8)).resize((pw, ph), Image.LANCZOS)).astype(np.float32)
        l, t = int(at[0] * w), int(at[1] * h)
        base[t:t + ph, l:l + pw] = patch
    before, after = base.copy(), base.copy()
    ph, pw = patch.shape[:2]
    if scale == 1.0:
        fill = img[t:t + ph, max(0, l - pw):l]
        if fill.shape[1] == pw:
            after[t:t + ph, l:l + pw] = fill
    else:
        after[t:t + ph, l:l + pw] = img[t:t + ph, l:l + pw]
    nl = l + int(dx_frac * w)
    after[t:t + ph, nl:nl + pw] = patch[:, :max(0, min(pw, w - nl))]
    return before, after


def _light(img, region, delta):
    out = img.copy()
    h, w, _ = img.shape
    l, t, r, b = (int(region[0] * w), int(region[1] * h), int(region[2] * w), int(region[3] * h))
    out[t:b, l:r] = np.clip(out[t:b, l:r] + delta, 0, 255)
    return out


def _dim_left(img, factor):
    out = img.copy()
    out[:, :img.shape[1] // 2] *= factor
    return out


def _cover(img, fraction, cloth):
    out = img.copy()
    h, w, _ = img.shape
    sh, sw = int(np.sqrt(fraction) * h), int(np.sqrt(fraction) * w)
    out[h - sh:, :sw] = np.asarray(Image.fromarray(cloth.astype(np.uint8)).resize((sw, sh), Image.LANCZOS)).astype(np.float32)
    return out


def scenarios(img, box, cloth):
    px_per_degree = img.shape[1] / 69.6  # 背面メイン（横の画角 約 70°）
    s = [
        ("何も動かない（ざらつき 小）", img, img, 2, False),
        ("何も動かない（暗い所のざらつき）", img, img, 8, False),
        ("自動露出で全体が 8% 明るく", img, np.clip(img * 1.08, 0, 255), 2, False),
        ("照明: 画面の右半分だけ +35", img, _light(img, (0.5, 0, 1, 1), 35), 2, False),
        ("照明: 窓など一部（1/4）が +60", img, _light(img, (0.6, 0, 1, 0.6), 60), 2, False),
        ("照明: 部屋全体が暗くなる（0.75 倍）", img, img * 0.75, 2, False),
        ("照明: 片側の照明が消える（左半分 0.7 倍）", img, _dim_left(img, 0.7), 2, False),
    ]
    for deg in (0.3, 1.0, 1.9):
        s.append((f"手ぶれ {deg}°（ジャイロでは止まっている扱い）", img, _shift(img, int(round(deg * px_per_degree))), 2, False))
    if box:
        b, a = _move_patch(img, box, 0.02)
        s.append(("近くの人が少し動く（幅の 2%）", b, a, 2, True))
        b, a = _move_patch(img, box, 0.06)
        s.append(("近くの人が 1 歩動く（幅の 6%）", b, a, 2, True))
        b, a = _move_patch(img, box, 0.02, scale=0.3)
        s.append(("遠くの小さな人が動く（大きさ 0.3 倍）", b, a, 2, "遠く"))
    s.append(("すぐ近くを人が覆う（本物の布・画面の 40%）", img, _cover(img, 0.4, cloth), 2, True))
    s.append(("すぐ近くを人が覆う（本物の布・画面の 25%）", img, _cover(img, 0.25, cloth), 2, True))
    return s


def main(paths, repeat=1):
    global RNG
    images = [upright(p) for p in paths]
    score = {}
    wrong = {}
    for run in range(repeat):
        RNG = np.random.default_rng(1 + run)
        for path, img in zip(paths, images):
            score_photo(path, img, score, wrong)
    print(f"## 期待どおりに判定できた数（乱数を変えて {repeat} 回の合計）")
    print("| 判定 | " + " | ".join(PRESETS) + " | 合計 |")
    print("|---|" + "---|" * (len(PRESETS) + 1))
    for rules, name in ((False, "これまで"), (True, "今")):
        cells = [score[(rules, p)] for p in PRESETS]
        print(f"| {name} | " + " | ".join(f"{c[0]}/{c[1]}" for c in cells)
              + f" | **{sum(c[0] for c in cells)}/{sum(c[1] for c in cells)}** |")
    print("\n## 今の判定でも期待と違った場面")
    print("| 写真 | 場面 | 撮り方 | 結果 | 回数 | 理由（例） |")
    print("|---|---|---|---|---|---|")
    for (file, label, preset, saved), whys in wrong.items():
        print(f"| {file} | {label} | {preset} | {'保存' if saved else '保存しない'} | {len(whys)}/{repeat} | {whys[0]} |")


def score_photo(path, img, score, wrong):
    """写真 1 枚から場面を作り、撮り方ごとに「これまで」と「今」の判定を採点する。"""
    people = [d for d in detect(img) if d[0] == "PERSON" and d[1] >= 0.4]
    box = max(people, key=lambda d: (d[2][2] - d[2][0]) * (d[2][3] - d[2][1]))[2] if people else None
    # 覆う布の模様: 写真の中で一番大きく写った人の枠の中（無ければ写真の真ん中）。
    h, w, _ = img.shape
    cb = box or (0.25, 0.25, 0.75, 0.75)
    cloth = img[int(cb[1] * h):int(cb[3] * h), int(cb[0] * w):int(cb[2] * w)]
    for label, a, b, sigma, expect in scenarios(img, box, cloth):
        na, nb = _noisy(a, sigma), _noisy(b, sigma)
        da, db = detect(na), detect(nb)
        for preset in PRESETS:
            if expect == "遠く" and preset != "外・遠く":
                continue  # 遠くの人は「外・遠く」で拾えればよい
            want = True if expect == "遠く" else expect
            for rules in (False, True):
                saved, why = decide(na, nb, da, db, preset, rules)
                cell = score.setdefault((rules, preset), [0, 0])
                cell[0] += saved == want
                cell[1] += 1
                if rules and saved != want:
                    wrong.setdefault((os.path.basename(path), label, preset, saved), []).append(why)


if __name__ == "__main__":
    args = sys.argv[1:]
    repeat = 1
    if args[:1] == ["--repeat"]:
        repeat = int(args[1])
        args = args[2:]
    if not args:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    main(args, repeat)
