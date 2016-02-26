package com.tapadoo.slacknotifier;

import jetbrains.buildServer.serverSide.SRunningBuild;
import org.joda.time.Duration;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;

/**
 * Created by jbako on 2/26/16.
 */

public class BuildDuration {
    private static PeriodFormatter durationFormatter = new PeriodFormatterBuilder()
            .printZeroRarelyFirst()
            .appendHours()
            .appendSuffix(" hour", " hours")
            .appendSeparator(" ")
            .printZeroRarelyLast()
            .appendMinutes()
            .appendSuffix(" minute", " minutes")
            .appendSeparator(" and ")
            .appendSeconds()
            .appendSuffix(" second", " seconds")
            .toFormatter();

    private final Duration buildDuration;

    public BuildDuration(SRunningBuild build) {
        buildDuration = new Duration(1000 * build.getDuration());
    }

    @Override
    public String toString() {
        return durationFormatter.print(buildDuration.toPeriod());
    }
}
