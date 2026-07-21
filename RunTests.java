import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.engine.discovery.DiscoverySelectors;
public class RunTests {
    public static void main(String[] args) {
        LauncherDiscoveryRequest req = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("tests")).build();
        SummaryGeneratingListener l = new SummaryGeneratingListener();
        LauncherFactory.create().execute(req, l);
        var s = l.getSummary();
        s.getFailures().forEach(f -> System.out.println("FAIL: "
            + f.getTestIdentifier().getDisplayName() + " - " + f.getException().getMessage()));
        System.out.println("Tests run: " + s.getTestsStartedCount()
            + ", failed: " + s.getTestsFailedCount()
            + ", succeeded: " + s.getTestsSucceededCount());
        if (s.getTestsFailedCount() > 0) System.exit(1);
    }
}
