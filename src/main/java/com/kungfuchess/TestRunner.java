package com.kungfuchess;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.discovery.DiscoverySelectors;

import java.io.PrintWriter;

public class TestRunner {
    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("tests"))
            .build();

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.discover(request);
        launcher.execute(request, listener);

        listener.getSummary().printFailuresTo(new PrintWriter(System.out, true));
        long failed = listener.getSummary().getTestsFailedCount();
        long succeeded = listener.getSummary().getTestsSucceededCount();
        long skipped = listener.getSummary().getTestsSkippedCount();
        System.out.println("Results: " + succeeded + " passed, " + failed + " failed, " + skipped + " skipped");
        System.exit(failed > 0 ? 1 : 0);
    }
}
