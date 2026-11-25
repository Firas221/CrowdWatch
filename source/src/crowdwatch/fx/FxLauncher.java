package crowdwatch.fx;

import javafx.application.Application;

/**
 * Starts the JavaFX console.
 * <p>
 * A separate launcher class (one that does not extend {@link Application}) lets the console run
 * with JavaFX on the class path as well as on the module path. Use the run-fx scripts in the
 * program folder, which pick the JavaFX files for the computer's system.
 */
public final class FxLauncher {
    // only main
    private FxLauncher() {}

    /**
     * @param args none for the venue chooser, or a venue code or .venue file to open directly
     */
    public static void main(String[] args) { Application.launch(FxApp.class, args); }
}
