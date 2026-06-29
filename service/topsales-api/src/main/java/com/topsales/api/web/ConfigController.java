package com.topsales.api.web;

import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Window;

import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/config} — the UI-relevant tweakables (from {@link TopsalesProperties}) the
 * dashboard builds its controls from, so the {@code k}/window/channel choices are config-driven
 * rather than hardcoded in the page.
 *
 * <p>A demo/dev affordance (like {@link TenantsController}): it exposes the central read config so
 * the static dashboard can stay a thin view. The option <em>sets</em> come from the domain enums
 * ({@link Window}, {@link ChannelFilter}) — the authoritative valid values — while the defaults and
 * {@code k} choices come from {@code topsales.read.*}.
 */
@RestController
public class ConfigController {

    private final TopsalesProperties props;

    public ConfigController(TopsalesProperties props) {
        this.props = props;
    }

    @GetMapping("/api/v1/config")
    public UiConfig config() {
        TopsalesProperties.Read read = props.read();
        return new UiConfig(
                read.kOptions(),
                read.kDefault(),
                Arrays.stream(Window.values()).map(Window::wire).toList(),
                read.windowDefault(),
                Arrays.stream(ChannelFilter.values()).map(ChannelFilter::wire).toList(),
                read.channelDefault());
    }

    /** The dashboard's control configuration. Option lists are lowercase wire values. */
    public record UiConfig(
            List<Integer> kOptions,
            int kDefault,
            List<String> windowOptions,
            String windowDefault,
            List<String> channelOptions,
            String channelDefault) {}
}
