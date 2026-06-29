package com.topsales.datagen.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.Channel;
import com.topsales.datagen.SeedConfig.HveSpec;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** The HVE calendar's channel asymmetry and calendar anchors. */
class HveCalendarTest {

    // Same magnitudes as data/seed/seed-config.json's hve.* block.
    private final HveCalendar hve =
            new HveCalendar(new HveSpec(5.0, 3.0, 1.5, 6.0, 4.0, 1.5, 1.3, 2.0, 0.7));

    @Test
    void blackFriday_isThe4thFridayOfNovember() {
        LocalDate bf = HveCalendar.blackFriday(2025);
        assertThat(bf.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(bf.getMonthValue()).isEqualTo(11);
        assertThat(bf).isEqualTo(LocalDate.of(2025, 11, 28));
    }

    @Test
    void blackFriday_isOfflineHeavy_cyberMonday_isOnlineHeavy() {
        LocalDate bf = HveCalendar.blackFriday(2025);
        LocalDate cm = bf.plusDays(3);

        assertThat(hve.multiplier(bf, Channel.OFFLINE))
                .isGreaterThan(hve.multiplier(bf, Channel.ONLINE));
        assertThat(hve.multiplier(cm, Channel.ONLINE))
                .isGreaterThan(hve.multiplier(cm, Channel.OFFLINE));
    }

    @Test
    void ordinaryDay_isNeutral() {
        assertThat(hve.multiplier(LocalDate.of(2025, 3, 10), Channel.ONLINE)).isEqualTo(1.0);
        assertThat(hve.multiplier(LocalDate.of(2025, 3, 10), Channel.OFFLINE)).isEqualTo(1.0);
    }

    @Test
    void december_rampsUp_thenDipsAfterChristmas() {
        double early = hve.multiplier(LocalDate.of(2025, 12, 2), Channel.ONLINE);
        double late = hve.multiplier(LocalDate.of(2025, 12, 23), Channel.ONLINE);
        double afterDip = hve.multiplier(LocalDate.of(2025, 12, 28), Channel.ONLINE);

        assertThat(late).isGreaterThan(early).isGreaterThan(1.0);
        assertThat(afterDip).isLessThan(1.0);
    }
}
