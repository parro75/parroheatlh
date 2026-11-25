package fr.parro.santesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class WeighReminderTest {
    // 26/11/2026 est un jeudi (4)
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    @Test fun desactive() = assertNull(WeighReminder.next(at(26, 6), WeighReminder.OFF, 1, 450))

    @Test fun quotidien_avantLHeure_aujourdhui() =
        assertEquals(at(26, 7, 30), WeighReminder.next(at(26, 6), WeighReminder.DAILY, 1, 450))

    @Test fun quotidien_apresLHeure_demain() =
        assertEquals(at(27, 7, 30), WeighReminder.next(at(26, 7, 30), WeighReminder.DAILY, 1, 450))

    @Test fun hebdo_jeudiAvantLHeure_aujourdhui() =
        assertEquals(at(26, 7, 0), WeighReminder.next(at(26, 6), WeighReminder.WEEKLY, 4, 420))

    @Test fun hebdo_jeudiApresLHeure_semaineSuivante() =
        assertEquals(LocalDateTime.of(2026, 12, 3, 7, 0), WeighReminder.next(at(26, 8), WeighReminder.WEEKLY, 4, 420))

    @Test fun hebdo_dimanche() =
        assertEquals(at(29, 9, 0), WeighReminder.next(at(26, 8), WeighReminder.WEEKLY, 7, 540))
}
