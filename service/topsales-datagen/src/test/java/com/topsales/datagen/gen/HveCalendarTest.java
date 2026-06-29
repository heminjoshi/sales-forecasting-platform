package com.topsales.datagen.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.Channel;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** The HVE calendar's channel asymmetry and calendar anchors. */
class HveCalendarTest {

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

        assertThat(HveCalendar.multiplier(bf, Channel.OFFLINE))
                .isGreaterThan(HveCalendar.multiplier(bf, Channel.ONLINE));
        assertThat(HveCalendar.multiplier(cm, Channel.ONLINE))
                .isGreaterThan(HveCalendar.multiplier(cm, Channel.OFFLINE));
    }

    @Test
    void ordinaryDay_isNeutral() {
        assertThat(HveCalendar.multiplier(LocalDate.of(2025, 3, 10), Channel.ONLINE)).isEqualTo(1.0);
        assertThat(HveCalendar.multiplier(LocalDate.of(2025, 3, 10), Channel.OFFLINE)).isEqualTo(1.0);
    }

    @Test
    void december_rampsUp_thenDipsAfterChristmas() {
        double early = HveCalendar.multiplier(LocalDate.of(2025, 12, 2), Channel.ONLINE);
        double late = HveCalendar.multiplier(LocalDate.of(2025, 12, 23), Channel.ONLINE);
        double afterDip = HveCalendar.multiplier(LocalDate.of(2025, 12, 28), Channel.ONLINE);

        assertThat(late).isGreaterThan(early).isGreaterThan(1.0);
        assertThat(afterDip).isLessThan(1.0);
    }
}
