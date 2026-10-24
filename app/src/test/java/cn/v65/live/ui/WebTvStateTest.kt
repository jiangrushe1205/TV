package cn.v65.live.ui

import cn.v65.live.data.Channel
import org.junit.Assert.*
import org.junit.Test

class WebTvStateTest {
    private val c1 = WebTvState.DEFAULT_CHANNEL
    private val c3 = Channel("600001801", "CCTV3", group = "yangshi")
    private val hunan = Channel("600002475", "湖南卫视", group = "weishi")
    private val channels = listOf(c1, c3, hunan)

    @Test fun restoresSavedChannelAndFallsBackToCctv1() {
        val state = WebTvState()
        assertEquals(c1, state.updateCatalog(channels, null))
        assertEquals(c3, state.updateCatalog(channels, c3.id))
        assertEquals(c1, state.updateCatalog(channels, "999999999"))
        assertEquals(c1, state.updateCatalog(emptyList(), hunan.id))
    }

    @Test fun repairsInvalidOrDuplicateCatalogWithoutDroppingValidOrder() {
        val state = WebTvState()
        state.updateCatalog(listOf(c1, c1, Channel("bad", "bad"), c3, hunan), c1.id)
        assertEquals(channels, state.channels)
        state.updateCatalog(listOf(hunan), "missing")
        assertEquals(listOf(c1, hunan), state.channels)
    }

    @Test fun filtersGroupsAndFavoritesInOfficialOrder() {
        val state = WebTvState()
        state.updateCatalog(channels)
        assertEquals(listOf(c1, c3), state.visibleChannels(emptySet()))
        state.nextGroup(1)
        assertEquals(listOf(hunan), state.visibleChannels(emptySet()))
        state.nextGroup(1)
        assertEquals(listOf(c3, hunan), state.visibleChannels(setOf(hunan.id, c3.id, "missing")))
        assertTrue(state.visibleChannels(emptySet()).isEmpty())
        state.nextGroup(1)
        assertEquals(WebTvState.Group.CCTV, state.group)
        state.nextGroup(-1)
        assertEquals(WebTvState.Group.FAVORITES, state.group)
    }

    @Test fun channelSwitchingWrapsBothEndsWithoutSelectingOnBrowse() {
        val state = WebTvState()
        state.updateCatalog(channels)
        assertEquals(hunan, state.adjacent(-1))
        assertEquals(c1, state.current)
        state.beginPage(hunan)
        assertEquals(c1, state.adjacent(1))
        state.beginPage(c3)
        assertEquals(c1, state.adjacent(-1))
        assertEquals(hunan, state.adjacent(1))
    }

    @Test fun backDismissesEveryOverlayBeforeRequestingExit() {
        val state = WebTvState()
        WebTvState.Overlay.entries.filter { it != WebTvState.Overlay.NONE }.forEach {
            state.overlay = it
            assertEquals(WebTvState.BackAction.DISMISSED_OVERLAY, state.back())
            assertEquals(WebTvState.Overlay.NONE, state.overlay)
            assertEquals(WebTvState.BackAction.CONFIRM_EXIT, state.back())
        }
    }

    @Test fun reloadSwitchAndBackgroundInvalidateOldCallbacks() {
        val state = WebTvState()
        state.updateCatalog(channels)
        val first = state.beginPage(c1)
        val refresh = state.beginPage(c1)
        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(refresh))
        val switched = state.beginPage(c3)
        assertFalse(state.isCurrent(refresh))
        assertTrue(state.isCurrent(switched))
        state.invalidateCallbacks()
        assertFalse(state.isCurrent(switched))
    }
}
