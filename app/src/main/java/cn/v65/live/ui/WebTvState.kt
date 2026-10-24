package cn.v65.live.ui

import cn.v65.live.data.Channel

/** Remote navigation and page identity, independent of Android views and WebView callbacks. */
class WebTvState {
    enum class Overlay { NONE, CHANNELS, TOOLBAR, POINTER }
    enum class Group { CCTV, SATELLITE, FAVORITES }
    enum class BackAction { DISMISSED_OVERLAY, CONFIRM_EXIT }
    data class PageToken(val generation: Long, val channelId: String)

    var channels: List<Channel> = listOf(DEFAULT_CHANNEL)
        private set
    var current: Channel = DEFAULT_CHANNEL
        private set
    var overlay = Overlay.NONE
    var group = Group.CCTV
    private var generation = 0L
    val token: PageToken get() = PageToken(generation, current.id)

    fun updateCatalog(items: List<Channel>, preferredId: String? = current.id): Channel {
        val valid = items.filter { validId(it.id) && it.name.isNotBlank() }.distinctBy { it.id }
        channels = valid.ifEmpty { listOf(DEFAULT_CHANNEL) }
        val next = channels.firstOrNull { it.id == preferredId }
            ?: channels.firstOrNull { it.id == DEFAULT_CHANNEL.id }
            ?: DEFAULT_CHANNEL
        if (channels.none { it.id == next.id }) channels = listOf(next) + channels
        if (current.id != next.id) generation++
        current = next
        return current
    }

    fun beginPage(channel: Channel = current): PageToken {
        require(validId(channel.id))
        current = channel
        generation++
        return token
    }

    fun invalidateCallbacks() { generation++ }
    fun isCurrent(value: PageToken) = token == value

    fun adjacent(step: Int): Channel {
        val index = channels.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        return channels[((index + step) % channels.size + channels.size) % channels.size]
    }

    fun visibleChannels(favorites: Set<String>): List<Channel> = channels.filter {
        when (group) {
            Group.CCTV -> it.group == "yangshi"
            Group.SATELLITE -> it.group == "weishi"
            Group.FAVORITES -> it.id in favorites
        }
    }

    fun nextGroup(step: Int) {
        val groups = Group.entries
        group = groups[((group.ordinal + step) % groups.size + groups.size) % groups.size]
    }

    fun back(): BackAction = if (overlay == Overlay.NONE) BackAction.CONFIRM_EXIT else {
        overlay = Overlay.NONE
        BackAction.DISMISSED_OVERLAY
    }

    companion object {
        val DEFAULT_CHANNEL = Channel("600001859", "CCTV1", group = "yangshi")
        fun validId(value: String?) = value?.matches(Regex("[0-9]{5,20}")) == true
    }
}
