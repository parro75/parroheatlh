package fr.parro.santesync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class MorningWorkerTest {
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 26, h, m)

    @Test fun dansLeCreneau_15min() = assertEquals(Duration.ofMinutes(15), MorningWorker.delayFrom(at(6, 10)))

    @Test fun avant5h_attendre5h() = assertEquals(Duration.ofMinutes(150), MorningWorker.delayFrom(at(2, 30)))

    @Test fun finDeCreneau_lendemain5h() =
        assertEquals(Duration.ofHours(18).plusMinutes(10), MorningWorker.delayFrom(at(10, 50)))

    @Test fun apresMidi_lendemain5h() = assertEquals(Duration.ofHours(15), MorningWorker.delayFrom(at(14, 0)))

    @Test fun pile5h_15min() = assertEquals(Duration.ofMinutes(15), MorningWorker.delayFrom(at(5, 0)))
}
