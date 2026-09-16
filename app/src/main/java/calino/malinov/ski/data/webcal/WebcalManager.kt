package calino.malinov.ski.data.webcal

import calino.malinov.ski.data.caldav.ICalMapper
import calino.malinov.ski.data.model.CalEvent
import calino.malinov.ski.data.model.WebcalForm
import calino.malinov.ski.data.model.WebcalSubscription
import calino.malinov.ski.data.repository.CalDavRepository
import calino.malinov.ski.data.repository.CalinoCalendar
import calino.malinov.ski.data.repository.WebcalOverlay
import calino.malinov.ski.data.repository.WebcalSubscriptionStore
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Fetches ICS feeds and publishes them as read-only calendars on the live
 * repository. Cache first, network second: a cold start should paint last
 * time's events without waiting on Google.
 */
class WebcalManager(
    private val store: WebcalSubscriptionStore,
    private val repository: CalDavRepository,
    private val fetcher: WebcalFetcher,
    private val cache: WebcalCache,
    private val scope: CoroutineScope,
    private val mapper: ICalMapper = ICalMapper(),
    private val today: () -> LocalDate = { LocalDate.now() },
    private val windowMonths: Long = 24L,
) {
    fun restoreFromCache() {
        publishOverlay()
    }

    fun restore() {
        restoreFromCache()
        scope.launch { syncDue() }
    }

    suspend fun add(form: WebcalForm): WebcalSubscription {
        val url = WebcalUrl.requireHttpUrl(form.url).toString()
        val name = form.name.trim().ifBlank { WebcalUrl.hostOf(url) }
        val ics = fetcher.fetchIcs(url)
        val subscription = store.add(
            name = name,
            url = url,
            color = form.color,
            refreshIntervalMinutes = form.refreshIntervalMinutes,
            notifyReminders = form.notifyReminders,
        )
        cache.save(subscription.id, ics)
        store.markFetched(subscription.id)
        publishOverlay()
        return subscription
    }

    fun remove(id: String) {
        cache.delete(id)
        store.remove(id)
        publishOverlay()
    }

    suspend fun sync(id: String) {
        val subscription = store.subscriptions().firstOrNull { it.id == id } ?: return
        val ics = fetcher.fetchIcs(subscription.url)
        cache.save(subscription.id, ics)
        store.markFetched(subscription.id)
        publishOverlay()
    }

    suspend fun syncDue() {
        store.subscriptions().filter(::isDue).forEach { subscription ->
            runCatching { sync(subscription.id) }
        }
    }

    suspend fun syncAll() {
        store.subscriptions().forEach { subscription ->
            runCatching { sync(subscription.id) }
        }
    }

    fun setVisible(id: String, visible: Boolean) {
        store.setVisible(id, visible)
        publishOverlay()
    }

    fun setNotifyReminders(id: String, notify: Boolean) {
        store.setNotifyReminders(id, notify)
        publishOverlay()
    }

    fun setName(id: String, name: String) {
        store.setName(id, name)
        publishOverlay()
    }

    fun setColor(id: String, color: Long) {
        store.setColor(id, color)
        publishOverlay()
    }

    private fun isDue(subscription: WebcalSubscription): Boolean {
        val last = subscription.lastFetchedAt ?: return true
        val interval = subscription.refreshIntervalMinutes.coerceAtLeast(1)
        return Instant.now().isAfter(last.plusSeconds(interval * 60L))
    }

    private fun publishOverlay() {
        val windowStart = today().minusMonths(windowMonths)
        val windowEnd = today().plusMonths(windowMonths)
        val calendars = mutableListOf<CalinoCalendar>()
        val events = mutableListOf<CalEvent>()
        store.subscriptions().forEach { subscription ->
            calendars += CalinoCalendar(
                id = subscription.calendarId,
                name = subscription.name,
                color = subscription.color,
                readOnly = true,
                components = setOf("VEVENT"),
                visible = subscription.visible,
                showTasksInViews = false,
                notifyReminders = subscription.notifyReminders,
            )
            val ics = cache.load(subscription.id) ?: return@forEach
            val parsed = runCatching {
                mapper.parse(
                    icalText = ics,
                    calendarId = subscription.calendarId,
                    color = subscription.color,
                    href = subscription.calendarId,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                )
            }.getOrNull() ?: return@forEach
            events += parsed.events
        }
        repository.setWebcalOverlay(WebcalOverlay(calendars, events))
    }
}
