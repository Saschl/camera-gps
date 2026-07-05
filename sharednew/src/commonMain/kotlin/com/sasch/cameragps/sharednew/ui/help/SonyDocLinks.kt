package com.sasch.cameragps.sharednew.ui.help

/**
 * Official Sony help-guide pages linked from the troubleshooting guide. The pages are
 * model-specific on purpose: they document the exact camera menu items the guide refers
 * to, and the same menus exist on the other bodies of that generation.
 */
object SonyDocLinks {

    /**
     * α6400 — [Loc. Info. Link Set.] menu, including [Auto Time Correct.] /
     * [Auto Area Adjust.] and the note that [Bluetooth Rmt Ctrl] must be off.
     */
    const val LOCATION_LINK_MENU_A6400 =
        "https://helpguide.sony.net/ilc/1810/v1/en/contents/TP0002273573.html"

    /** ZV-E10 — [Location Info. Link Set.] menu. */
    const val LOCATION_LINK_MENU_ZVE10 =
        "https://helpguide.sony.net/ilc/2070/v1/en/contents/TP0001212414.html"

    /** α6400 — [Bluetooth Rmt Ctrl] menu; documents the conflict with location linkage. */
    const val BLUETOOTH_REMOTE_MENU_A6400 =
        "https://helpguide.sony.net/ilc/1810/v1/en/contents/TP0002392817.html"
}
