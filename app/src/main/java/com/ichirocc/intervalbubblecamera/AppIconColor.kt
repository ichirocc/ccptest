package com.ichirocc.intervalbubblecamera

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

enum class AppIconColor(
    val storageKey: String,
    @get:StringRes val labelRes: Int,
    @get:ColorRes val colorRes: Int,
    @get:DrawableRes val iconRes: Int,
    val launcherAliasSuffix: String,
) {
    BLUE(
        storageKey = "blue",
        labelRes = R.string.icon_color_blue,
        colorRes = R.color.icon_blue,
        iconRes = R.drawable.ic_app_icon,
        launcherAliasSuffix = "launcher.Blue",
    ),
    GREEN(
        storageKey = "green",
        labelRes = R.string.icon_color_green,
        colorRes = R.color.icon_green,
        iconRes = R.drawable.ic_app_icon_green,
        launcherAliasSuffix = "launcher.Green",
    ),
    RED(
        storageKey = "red",
        labelRes = R.string.icon_color_red,
        colorRes = R.color.icon_red,
        iconRes = R.drawable.ic_app_icon_red,
        launcherAliasSuffix = "launcher.Red",
    ),
    ORANGE(
        storageKey = "orange",
        labelRes = R.string.icon_color_orange,
        colorRes = R.color.icon_orange,
        iconRes = R.drawable.ic_app_icon_orange,
        launcherAliasSuffix = "launcher.Orange",
    ),
    PURPLE(
        storageKey = "purple",
        labelRes = R.string.icon_color_purple,
        colorRes = R.color.icon_purple,
        iconRes = R.drawable.ic_app_icon_purple,
        launcherAliasSuffix = "launcher.Purple",
    ),
    PINK(
        storageKey = "pink",
        labelRes = R.string.icon_color_pink,
        colorRes = R.color.icon_pink,
        iconRes = R.drawable.ic_app_icon_pink,
        launcherAliasSuffix = "launcher.Pink",
    ),
    ;

    companion object {
        val DEFAULT = BLUE

        fun fromStorageKey(key: String?): AppIconColor =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
