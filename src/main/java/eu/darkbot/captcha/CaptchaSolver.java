package eu.darkbot.captcha;

import com.github.manolo8.darkbot.gui.utils.Popups;
import com.github.manolo8.darkbot.utils.CaptchaAPI;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CaptchaSolver implements CaptchaAPI {

    private static final String url = "https://www.darkorbit.com/";
    private static boolean isFirstLogin = true;

    static {
        // Due to how we use webview (creating a new one each time it is requested) we need the platform
        // not to finish once we dispose it (the pop-up closes).
        Platform.setImplicitExit(false);
    }

    @Override
    public Map<String, String> solveCaptcha(URL url, String frontPage) {
        if (!url.toString().contains("lp") || !frontPage.contains("recaptcha_image")) return Collections.emptyMap();

        System.out.println("Captcha detected, opening solver...");

        String response = getResponse();
        System.gc();

        if (response != null) {
            isFirstLogin = false;
            System.out.println("Captcha solved successfully");
            return Collections.singletonMap("g-recaptcha-response", response);
        }
        System.out.println("No solution to the captcha was provided or timed out.");
        return Collections.emptyMap();
    }

    private String getResponse() {
        CompletableFuture<String> key = new CompletableFuture<>();
        SwingUtilities.invokeLater(new SolverJFXPanel(key));

        try {
            return key.get(190, TimeUnit.SECONDS); // Worst-case scenario, timeout after 190s
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static class SolverJFXPanel extends JFXPanel implements Runnable {

        private final CompletableFuture<String> key;
        private Timeline timeline;
        private JDialog dialog;
        private WebEngine engine;
        private WebView webView;
        private ChangeListener<Worker.State> onPageLoad;
        private int maxFrames;

        public SolverJFXPanel(CompletableFuture<String> key) {
            this.key = key;
            maxFrames = isFirstLogin ? 40 * 2 : 15 * 2;
            setPreferredSize(new Dimension(460, 535));

            key.whenComplete((k, t) -> {
                if (timeline != null) {
                    timeline.stop();
                    timeline = null;
                }
                if (dialog != null) {
                    dialog.setVisible(false);
                }
                if (engine != null) {
                    engine.getLoadWorker().stateProperty().removeListener(onPageLoad);
                    onPageLoad = null;
                    engine.getLoadWorker().cancel();
                    engine = null;
                }
            });

            Platform.runLater(this::prepareWebView);
        }

        @Override
        public void run() {
            JPanel panel = new JPanel(new MigLayout("fill, insets 0"));
            panel.add(this);

            JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE,
                    JOptionPane.DEFAULT_OPTION, null, new Object[]{}, null);
            pane.setBorder(BorderFactory.createEmptyBorder(0, 0, -4, 0));

            Popups.of("Manual captcha solver", pane)
                    .callback(d -> this.dialog = d)
                    .showSync();
            // User closed pop-up without a solution to the key, solve with null.
            if (!key.isDone()) key.complete(null);
        }

        private void prepareWebView() {
            webView = new WebView();
            engine = webView.getEngine();

            StackPane root = new StackPane();
            ProgressIndicator progressIndicator = new ProgressIndicator();
            root.getChildren().addAll(progressIndicator);
            setScene(new Scene(root));

            webView.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
                Set<Node> deadSeaScrolls = webView.lookupAll(".scroll-bar");
                for (Node scroll : deadSeaScrolls) scroll.setVisible(false);
            });

            engine.getLoadWorker().stateProperty().addListener(onPageLoad = this::onPageLoad);

            engine.setUserAgent("BigpointClient/1.5.2");
            // avoid javafx user agent bug
            engine.loadContent("<html><body></body></html>", "text/html; charset=utf8");
            engine.load(url);
        }

        private void onPageLoad(ObservableValue<? extends Worker.State> obs, Worker.State oldState, Worker.State newState) {
            if (newState != Worker.State.SUCCEEDED || !engine.getLocation().equals(url)) return;

            setScene(new Scene(webView));
            getScene().setOnMouseClicked(event -> {
                maxFrames = 140 * 2; //140 seconds * 2 frames per second
                getScene().setOnMouseClicked(null);
            });

            String script = "" +
                    // Close cookie container, if it exists
                    "setTimeout(() => {" +
                    "  let qcContainer = document.getElementById('qc-cmp2-container');" +
                    "  if (qcContainer != null) qcContainer.remove();" +
                    "}, 500);" +
                    // Repeating function that tries to remove extra elements keeping captcha only
                    "function tryCleanPage(delay) {" +
                    "  if (delay > 1000) return;" + // We ran too many times, just stop
                    "  let container = document.getElementsByClassName('eh_mc_container eh_mc_table')[0];" +
                    "  let cap = document.getElementsByClassName('bgcdw_captcha')[0];" +
                    "  if (!container || !cap) {" +
                    "    setTimeout(() => tryCleanPage(delay + 25), delay);" + // Try to delay a bit more next time
                    "    return;" +
                    "  }" +
                    "  container.innerHTML = '';" +
                    "  container.appendChild(cap);" +
                    "  document.getElementsByTagName('body')[0].style.minWidth = 0;" +
                    "  let styles = [...document.getElementsByTagName('style')];" +
                    "  for (let i in styles) styles[i].remove();" +
                    "}" +
                    "tryCleanPage(5);";

            this.engine.executeScript(script);

            // Loaded the page twice? stop the previous timeline.
            if (timeline != null && timeline.getStatus() != Animation.Status.STOPPED)
                timeline.stop();

            this.timeline = new Timeline(new KeyFrame(Duration.seconds(0.5),
                    e -> Platform.runLater(this::checkResolved)));
            this.timeline.setCycleCount(Animation.INDEFINITE);
            this.timeline.play();
        }

        private void checkResolved() {
            if (this.dialog == null) return;

            String response = (String) engine.executeScript("grecaptcha ? grecaptcha.getResponse() : null");
            if (response != null && !response.isEmpty()) key.complete(response);
            else if (this.maxFrames > 0) {
                this.maxFrames--;
                this.dialog.setTitle("Manual captcha solver (" + (this.maxFrames / 2) + "s left)");
                if (this.maxFrames <= 0)
                    key.completeExceptionally(new TimeoutException("Took too long to solve captcha"));
            }
        }

    }

}
