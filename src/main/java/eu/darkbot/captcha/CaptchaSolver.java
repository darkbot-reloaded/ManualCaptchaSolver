package eu.darkbot.captcha;

import com.github.manolo8.darkbot.gui.utils.Popups;
import com.github.manolo8.darkbot.utils.CaptchaAPI;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.Scene;
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
import java.util.concurrent.TimeoutException;

public class CaptchaSolver implements CaptchaAPI {

    private static final String url = "https://www.darkorbit.com/";

    @Override
    public Map<String, String> solveCaptcha(URL url, String frontPage) {
        if (!url.toString().contains("lp") || !frontPage.contains("recaptcha_image")) return Collections.emptyMap();

        System.out.println("Captcha detected, opening solver...");

        CompletableFuture<String> key = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> new SolverJFXPanel(key));

        try {
            String response = key.get();
            if (response != null) return Collections.singletonMap("g-recaptcha-response", response);
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

        System.out.println("No solution to the captcha was provided, timed out.");
        return Collections.emptyMap();
    }

    private static class SolverJFXPanel extends JFXPanel {
        private final CompletableFuture<String> key;
        private final WebView webView;

        private Timeline timeline;
        private JDialog dialog;
        private int maxFrames = 120;

        public SolverJFXPanel(CompletableFuture<String> key) {
            this.key = key;
            this.webView = new WebView();
            setPreferredSize(new Dimension(460, 535));

            setScene(new Scene(webView));


            JPanel panel = new JPanel(new MigLayout("fill, insets 0"));
            panel.add(this);

            JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE,
                    JOptionPane.DEFAULT_OPTION, null, new Object[]{}, null);
            pane.setBorder(BorderFactory.createEmptyBorder(0, 0, -4, 0));

            key.whenComplete((k, t) -> closePopup());

            Platform.runLater(this::prepareWebView);
            Popups.showMessageSync("Manual captcha solver", pane, d -> this.dialog = d);
        }

        public void closePopup() {
            if (timeline != null) {
                timeline.stop();
                timeline = null;
            }
            if (dialog != null) {
                dialog.setVisible(false);
                dialog.dispose();
                dialog = null;
            }
        }

        private void prepareWebView() {
            webView.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
                Set<Node> deadSeaScrolls = webView.lookupAll(".scroll-bar");
                for (Node scroll : deadSeaScrolls) scroll.setVisible(false);
            });

            WebEngine engine = webView.getEngine();

            engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState != Worker.State.SUCCEEDED || !webView.getEngine().getLocation().equals(url)) return;

                String script = "" +
                        "function setup() {" +
                        "let qcContainer = document.getElementById('qc-cmp2-container');" +
                        "let container = document.getElementsByClassName('eh_mc_container eh_mc_table')[0];" +
                        "var cap = document.getElementsByClassName('bgcdw_captcha')[0];" +
                        "if (!qcContainer || !container || !cap) {" +
                        "    setTimeout() => setup(), 5);" +
                        "    return;" +
                        "}" +
                        "qcContainer.remove();" +
                        "container.innerHTML = '';" +
                        "container.appendChild(cap);" +
                        "document.getElementsByTagName('body')[0].style.minWidth = 0;" +
                        "let styles = [...document.getElementsByTagName('style')];" +
                        "for (let i in styles) styles[i].remove();" +
                        "}" +
                        "setup();";

                webView.getEngine().executeScript(script);

                // Loaded the page twice?
                if (timeline != null && timeline.getStatus() != Animation.Status.STOPPED)
                    timeline.stop();

                KeyFrame frame = new KeyFrame(Duration.seconds(0.5), e -> Platform.runLater(() -> {
                    if (dialog == null) return;

                    String response = (String) engine.executeScript("grecaptcha.getResponse()");
                    if (response != null) key.complete(response);
                    else if (maxFrames > 0) {
                        maxFrames--;
                        dialog.setTitle("Manual captcha solver (" + (maxFrames / 2) + "s left)");
                        if (maxFrames <= 0)
                            key.completeExceptionally(new TimeoutException("Took too long to solve captcha"));
                    }
                }));

                timeline = new Timeline(frame);
                timeline.setCycleCount(Animation.INDEFINITE);
                timeline.play();
            });

            engine.setUserAgent("BigpointClient/1.5.2");
            // avoid javafx user agent bug
            engine.loadContent("<html><body></body></html>", "text/html; charset=utf8");
            engine.load(url);
        }
    }

}
