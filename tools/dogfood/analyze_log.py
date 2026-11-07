#!/usr/bin/env python3
"""実機で確かめるための記録（アプリの「記録を送る」で送った CSV）を読み、設定の値が場面に合っているかを
まとめる。

使い方:
    python3 tools/dogfood/analyze_log.py ibc_log.1.csv ibc_log.csv > report.md

撮影の開始（kind=event, reason=start）ごとに 1 つの区切り（セッション）として、次をまとめる。
- 何回撮って、何回保存したか（1 時間あたり）。保存した理由・しなかった理由の内訳。
- 全画素・質を上げる設定・Ultra HDR で実際につなげたか。つなげなかった理由（bind_fallback）。
- あやしい保存: スマホが動いた回の手・体の一部、すぐ近くを覆った、歩きながら（撮っている人自身や
  となりの人を写したことが多い）。
- 閾値が合っているか: 動きが無いと判定した回（静かな回）と、動く人を見つけた回の「変化の広さ」の分布を比べ、
  設定の「変化の広さ」がその間にあるかを見る。静かな回の 99% が下回る値より少し上を勧める。

標準ライブラリだけで動く。
"""

from __future__ import annotations

import csv
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime

# 静かな回: 動くものが無いと判定した・検出を省いた回。
QUIET_REASONS = {"no_moving_target", "no_moving_target(box_jump)", "skipped_no_change", "no_change(no_detection:heat)", "no_change(no_detector)"}
# あやしい保存: 撮っている人自身やとなりの人を写したことが多い理由。
SUSPICIOUS_PREFIXES = ("phone_moved_person:HAND", "phone_moved_person:BODY_PART", "close_subject", "walking")


@dataclass
class Session:
    start: str
    settings: str
    shots: list[dict] = field(default_factory=list)
    events: list[dict] = field(default_factory=list)


def read_rows(paths: list[str]) -> list[dict]:
    rows: list[dict] = []
    for path in paths:
        with open(path, newline="", encoding="utf-8") as f:
            rows.extend(csv.DictReader(f))
    rows.sort(key=lambda r: r.get("time", ""))
    return rows


def split_sessions(rows: list[dict]) -> list[Session]:
    sessions: list[Session] = []
    current: Session | None = None
    for row in rows:
        if row.get("kind") == "event" and row.get("reason") == "start":
            current = Session(start=row["time"], settings=row.get("message", ""))
            sessions.append(current)
            continue
        if current is None:
            current = Session(start=row.get("time", ""), settings="(開始の記録なし)")
            sessions.append(current)
        (current.shots if row.get("kind") == "shot" else current.events).append(row)
    return sessions


def number(value: str) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round(p / 100 * (len(ordered) - 1))))
    return ordered[index]


def hours_between(first: str, last: str) -> float:
    try:
        a = datetime.fromisoformat(first)
        b = datetime.fromisoformat(last)
        return max((b - a).total_seconds() / 3600, 1 / 3600)
    except ValueError:
        return 0.0


def fmt_pct(value: float | None) -> str:
    return "—" if value is None else f"{value * 100:.2f}%"


def recommend_area(quiet: list[float], moving: list[float], current_permille: int | None) -> list[str]:
    """変化の広さ（area_permille）が、静かな回と動いた回の間にあるかを見て、勧める値を返す。"""
    lines: list[str] = []
    q99 = percentile(quiet, 99)
    m25 = percentile(moving, 25)
    if q99 is None:
        lines.append("- 静かな回がまだ無いので、閾値は判断できません。")
        return lines
    suggested = max(1, min(200, round(q99 * 1000 * 1.2 + 0.5)))
    lines.append(f"- 静かな回の変化の広さ: 中央値 {fmt_pct(percentile(quiet, 50))}・99% {fmt_pct(q99)}")
    if m25 is not None:
        lines.append(f"- 動く人を見つけた回の変化の広さ: 25% {fmt_pct(m25)}・中央値 {fmt_pct(percentile(moving, 50))}")
    if current_permille is not None:
        current = current_permille / 1000
        if current <= q99:
            lines.append(
                f"- **設定の変化の広さ {current_permille / 10:.1f}% は、静かな回の 99% 以下です。** "
                f"何も動いていないのに画像の変化だけで動いたとみなす回が出ます（人の位置で判定し直すので、"
                f"すぐ保存にはなりません）。{suggested / 10:.1f}% 以上を勧めます。"
            )
        elif m25 is not None and current > m25:
            lines.append(
                f"- **設定の変化の広さ {current_permille / 10:.1f}% は、動く人の回の 25% より大きいです。** "
                f"動く人の回の 4 分の 1 以上で画像の変化が閾値に届きません（人の位置の移動では拾えます）。"
                f"{suggested / 10:.1f}% 前後を勧めます。"
            )
        else:
            lines.append(f"- 設定の変化の広さ {current_permille / 10:.1f}% は、静かな回と動いた回の間にあり、合っています。")
    if m25 is not None and q99 >= m25:
        lines.append("- 静かな回と動いた回の変化の広さが重なっています。広さだけでは分けられないので、人の検出に頼っています。")
    return lines


def report(sessions: list[Session]) -> str:
    out: list[str] = ["# 実機での確かめ（記録のまとめ）", ""]
    for number_, session in enumerate(sessions, 1):
        shots = session.shots
        out.append(f"## セッション {number_}: {session.start}")
        out.append(f"設定: `{session.settings}`")
        out.append("")
        if not shots:
            out.append("撮影の記録がありません。")
            out.append("")
            continue
        cycles = {s["cycle"] for s in shots}
        saved_cycles = {s["cycle"] for s in shots if s.get("saved") == "1"}
        hours = hours_between(shots[0]["time"], shots[-1]["time"])
        per_hour = len(saved_cycles) / hours if hours else 0.0
        out.append("### 撮影と保存")
        out.append(f"- 撮影: {len(cycles)} 周（カメラごとの撮影 {len(shots)} 回）・約 {hours * 60:.0f} 分")
        out.append(f"- 保存: {len(saved_cycles)} 周（{len(saved_cycles) / len(cycles) * 100:.1f}%、1 時間あたり約 {per_hour:.0f} 回）")
        out.append("")

        # 理由の内訳
        reasons = Counter(s.get("reason", "") for s in shots)
        saving_reasons = Counter(s.get("reason", "") for s in shots if s.get("motion") == "1")
        out.append("### 判定の理由（カメラごとの撮影）")
        out.append("| 理由 | 回数 | うち動いたとみなした |")
        out.append("|---|---|---|")
        for reason, count in reasons.most_common():
            out.append(f"| `{reason or '(空)'}` | {count} | {saving_reasons.get(reason, 0)} |")
        out.append("")

        # あやしい保存
        suspicious = [s for s in shots if s.get("motion") == "1" and s.get("reason", "").startswith(SUSPICIOUS_PREFIXES)]
        out.append("### あやしい保存（撮っている人自身・となりの人のことが多い）")
        if suspicious:
            kinds = Counter(s["reason"] for s in suspicious)
            out.append(f"- {len(suspicious)} 回（動いたとみなした回の {len(suspicious) / max(1, sum(saving_reasons.values())) * 100:.0f}%）: "
                       + "、".join(f"`{k}` {v}" for k, v in kinds.most_common()))
            out.append("- 保存した写真と見比べて、意図した保存かを確かめてください。")
        else:
            out.append("- ありません。")
        out.append("")

        # カメラのつなぎ方
        binds = [e for e in session.events if e.get("reason") == "bind"]
        fallbacks = [e for e in session.events if e.get("reason") == "bind_fallback"]
        out.append("### カメラのつなぎ方")
        full = sum(1 for s in shots if s.get("full_resolution") == "1")
        enhanced = sum(1 for s in shots if s.get("enhanced") == "1")
        hdr = sum(1 for s in shots if s.get("ultra_hdr") == "1")
        out.append(f"- 全画素でつないだ撮影: {full}/{len(shots)}・質を上げる設定: {enhanced}/{len(shots)}・Ultra HDR: {hdr}/{len(shots)}")
        sizes = Counter(f"{s.get('image_width')}×{s.get('image_height')}" for s in shots if s.get("image_width"))
        if sizes:
            out.append("- 撮れた写真の大きさ: " + "、".join(f"{k}（{v} 回）" for k, v in sizes.most_common()))
        heats = Counter(s.get("heat", "") for s in shots)
        out.append("- 本体の温度: " + "、".join(f"{k} {v} 回" for k, v in heats.most_common()))
        if fallbacks:
            out.append(f"- **つなげなかった（ふつうの撮り方に戻した）: {len(fallbacks)} 回**")
            for message, count in Counter(e.get("message", "") for e in fallbacks).most_common(5):
                out.append(f"  - {count} 回: `{message}`")
        elif binds and full == 0 and enhanced == 0:
            out.append("- 全画素・質を上げる設定で一度もつないでいません（温度・夜景モード・機種の設定を確かめてください）。")
        failures = [e for e in session.events if e.get("reason") == "capture_failed"]
        if failures:
            out.append(f"- 撮れなかった: {len(failures)} 回（" + "、".join(
                f"{k} {v}" for k, v in Counter(e.get("camera", "") for e in failures).most_common()) + "）")
        out.append("")

        # 閾値
        out.append("### 閾値が合っているか（変化の広さ）")
        quiet = [v for s in shots if s.get("reason") in QUIET_REASONS and (v := number(s.get("changed_ratio"))) is not None]
        moving = [v for s in shots if s.get("reason", "").startswith("moving_target:")
                  and (v := number(s.get("changed_ratio"))) is not None]
        current = number(shots[-1].get("area_permille"))
        out.extend(recommend_area(quiet, moving, int(current) if current is not None else None))
        out.append("")
    return "\n".join(out)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    print(report(split_sessions(read_rows(argv[1:]))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
