package calino.malinov.ski.data.webcal

import calino.malinov.ski.data.caldav.CalDavFetcher
import calino.malinov.ski.data.caldav.DavHttp
import calino.malinov.ski.data.model.CalEvent
import calino.malinov.ski.data.repository.CalDavRepository
import calino.malinov.ski.data.repository.CalinoCalendar
import calino.malinov.ski.data.repository.WebcalOverlay
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebcalOverlayTest {
    private lateinit var scope: CoroutineScope

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    @After fun tearDown() { scope.cancel() }

    @Test fun `overlay events show without a CalDAV source and clear when removed`() {
        val repo = CalDavRepository(
            fetcher = CalDavFetcher(DavHttp()),
            scope = scope,
            today = { LocalDate.of(2026, 9, 16) },
        )
        val event = CalEvent(
            id = "overlay-1",
            title = "School pickup",
            color = 0xFF5B7FB5,
            start = LocalDateTime.of(2026, 9, 16, 15, 0),
            durationMinutes = 30,
            calendarId = "webcal:1",
        )
        repo.setWebcalOverlay(
            WebcalOverlay(
                calendars = listOf(
                    CalinoCalendar("webcal:1", "Katrina", 0xFF5B7FB5, readOnly = true, notifyReminders = false),
                ),
                events = listOf(event),
            ),
        )
        assertEquals(listOf("webcal:1"), repo.snapshot().calendars.map { it.id })
        assertEquals(listOf("School pickup"), repo.snapshot().events.map { it.title })
        assertEquals(true, repo.snapshot().calendars.single().readOnly)
        assertEquals(false, repo.snapshot().calendars.single().notifyReminders)

        repo.setWebcalOverlay(WebcalOverlay())
        assertTrue(repo.snapshot().events.none { it.calendarId == "webcal:1" })
    }
}
