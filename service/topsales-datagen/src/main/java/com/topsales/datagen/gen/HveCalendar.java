package com.topsales.datagen.gen;

import com.topsales.common.domain.Channel;
import com.topsales.datagen.SeedConfig.HveSpec;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * High-volume-event calendar as <em>recurring seasonality</em> (not outliers): the multiplier is a
 * deterministic function of the calendar date and channel, so the forecaster learns it as a repeating
 * pattern. Channels diverge — offline peaks on Black Friday, online on Cyber Monday (ADR-0010).
 *
 * <p>The calendar anchors (which dates are HVEs) stay in code; the channel-split <em>magnitudes</em>
 * come from {@link HveSpec} (data/seed/seed-config.json {@code hve.*}), so they're tunable without a
 * code change. Instance-based so the spec is injected once and reused per cell.
 */
public final class HveCalendar {

    private final HveSpec spec;

    public HveCalendar(HveSpec spec) {
        this.spec = spec;
    }

    /** Demand multiplier for {@code date} on {@code channel}; {@code 1.0} on ordinary days. */
    public double multiplier(LocalDate date, Channel channel) {
        LocalDate blackFriday = blackFriday(date.getYear());
        LocalDate cyberMonday = blackFriday.plusDays(3); // the following Monday

        if (date.equals(blackFriday)) {
            return channel == Channel.OFFLINE ? spec.blackFridayOffline() : spec.blackFridayOnline();
        }
        if (date.equals(cyberMonday)) {
            return channel == Channel.OFFLINE ? spec.cyberMondayOffline() : spec.cyberMondayOnline();
        }
        // Mid-year Prime-Day-style event (online-heavy).
        if (date.getMonthValue() == 7 && (date.getDayOfMonth() == 15 || date.getDayOfMonth() == 16)) {
            return channel == Channel.ONLINE ? spec.primeDayOnline() : spec.primeDayOffline();
        }
        // December: a pre-Christmas ramp (rampStart -> rampEnd over Dec 1–24), then a post-event dip.
        if (date.getMonthValue() == 12) {
            int dom = date.getDayOfMonth();
            if (dom >= 1 && dom <= 24) {
                return spec.decemberRampStart()
                        + (spec.decemberRampEnd() - spec.decemberRampStart()) * ((dom - 1) / 23.0);
            }
            if (dom >= 26) {
                return spec.decemberPostDip();
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
