package com.topsales.datagen.gen;

import com.topsales.common.domain.Channel;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * High-volume-event calendar as <em>recurring seasonality</em> (not outliers): the multiplier is a
 * deterministic function of the calendar date and channel, so the forecaster learns it as a repeating
 * pattern. Channels diverge — offline peaks on Black Friday, online on Cyber Monday (ADR-0010).
 */
public final class HveCalendar {

    private HveCalendar() {}

    /** Demand multiplier for {@code date} on {@code channel}; {@code 1.0} on ordinary days. */
    public static double multiplier(LocalDate date, Channel channel) {
        LocalDate blackFriday = blackFriday(date.getYear());
        LocalDate cyberMonday = blackFriday.plusDays(3); // the following Monday

        if (date.equals(blackFriday)) {
            return channel == Channel.OFFLINE ? 5.0 : 3.0;
        }
        if (date.equals(cyberMonday)) {
            return channel == Channel.OFFLINE ? 1.5 : 6.0;
        }
        // Mid-year Prime-Day-style event (online-heavy).
        if (date.getMonthValue() == 7 && (date.getDayOfMonth() == 15 || date.getDayOfMonth() == 16)) {
            return channel == Channel.ONLINE ? 4.0 : 1.5;
        }
        // December: a pre-Christmas ramp (1.3 -> 2.0 over Dec 1–24), then a small post-event dip.
        if (date.getMonthValue() == 12) {
            int dom = date.getDayOfMonth();
            if (dom >= 1 && dom <= 24) {
                return 1.3 + 0.7 * ((dom - 1) / 23.0);
            }
            if (dom >= 26) {
                return 0.7;
            }
        }
        return 1.0;
    }

    /** Black Friday = the 4th Friday of November. */
    public static LocalDate blackFriday(int year) {
        LocalDate nov1 = LocalDate.of(year, 11, 1);
        int toFirstFriday = (DayOfWeek.FRIDAY.getValue() - nov1.getDayOfWeek().getValue() + 7) % 7;
        return nov1.plusDays(toFirstFriday).plusWeeks(3);
    }
}
