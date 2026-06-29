package com.topsales.datagen;

import com.topsales.datagen.load.SeedLoader;
import com.topsales.datagen.load.TrickleRunner;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Dispatches on the program argument: {@code seed} (default) bulk-backfills aggregates;
 * {@code trickle} posts live events to the running API. The app exits when this returns.
 */
@Component
public class DatagenRunner implements ApplicationRunner {

    private final SeedLoader seedLoader;
    private final TrickleRunner trickleRunner;

    public DatagenRunner(SeedLoader seedLoader, TrickleRunner trickleRunner) {
        this.seedLoader = seedLoader;
        this.trickleRunner = trickleRunner;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> modes = args.getNonOptionArgs();
        String mode = modes.isEmpty() ? "seed" : modes.get(0);
        switch (mode) {
            case "seed" -> seedLoader.run();
            case "trickle" -> trickleRunner.run();
            default ->
                    throw new IllegalArgumentException(
                            "unknown mode: " + mode + " (expected: seed | trickle)");
        }
    }
}
