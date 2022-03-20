package eu.darkbot.captcha;

import com.github.manolo8.darkbot.gui.utils.Popups;
import com.github.manolo8.darkbot.utils.CaptchaAPI;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class CaptchaSolver implements CaptchaAPI {

    private static final String url = "https://www.darkorbit.com/";
    private static String key;
    private static boolean firstTime;

    private static void initFX() {
        JFXPanel jfxPanel = new JFXPanel();
        jfxPanel.setPreferredSize(new Dimension(460, 535));
        Platform.runLater(() -> {
            WebView webView = new WebView();
            jfxPanel.setScene(new Scene(webView));

            webView.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
                Set<Node> deadSeaScrolls = webView.lookupAll(".scroll-bar");
                for (Node scroll : deadSeaScrolls) {
                    scroll.setVisible(false);
                }
            });

            webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                if (newState == Worker.State.SUCCEEDED) {
                    final KeyFrame kf = new KeyFrame(Duration.seconds(0.3), e -> {
                        if (webView.getEngine().getLocation().equals(url)) {
                            webView.getEngine().executeScript("document.getElementById('qc-cmp2-container').remove();");
                            webView.getEngine().executeScript("var p = document.getElementsByClassName('eh_mc_container eh_mc_table')[0];" +
                                    "        var d = document.getElementsByClassName('bgcdw_captcha')[0];" +
                                    "        p.innerHTML = '';" +
                                    "        p.appendChild(d);");
                            webView.getEngine().executeScript("document.getElementsByTagName('body')[0].style.minWidth = 0;");
                            webView.getEngine().executeScript("while (document.getElementsByTagName('style').length !== 0){" +
                                    "    document.getElementsByTagName('style')[0].remove();}");
                        }

                        new Thread(() -> {
                            AtomicReference<String> res = new AtomicReference<>("");

                            while (res.get() == null || res.get().isEmpty()) {
                                Platform.runLater(() -> {
                                    res.set((String) webView.getEngine().executeScript("grecaptcha.getResponse()"));
                                });
                                try {
                                    Thread.sleep(400);
                                } catch (InterruptedException e1) {
                                    e1.printStackTrace();
                                }
                                if (res.get() != null && !res.get().isEmpty()) {
                                    System.out.println("captcha key: " + res);
                                    key = res.get();
                                    Platform.exit();
                                    for (Window w : JDialog.getWindows()) {
                                        if (w instanceof JDialog)
                                            if (((JDialog) w).getTitle().contains("Manual"))
                                                w.setVisible(false);
                                    }
                                }
                            }
                        }).start();
                    });
                    final Timeline timeline = new Timeline(kf);
                    Platform.runLater(timeline::play);
                }
            });

            webView.getEngine().setUserAgent("BigpointClient/1.5.2");
            webView.getEngine().loadContent("<html><body></body></html>", "text/html; charset=utf8"); //avoid bug of javafx of user agent
            webView.getEngine().load(url);

        });
        JPanel panel = new JPanel(new MigLayout("fill, insets 0"));

        panel.add(jfxPanel);
        JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[]{}, null);
        pane.setBorder(BorderFactory.createEmptyBorder(0, 0, -4, 0));
        Popups.showMessageSync("Manual captcha solver", pane);
    }

    @Override
    public Map<String, String> solveCaptcha(URL url, String frontPage) {
        if (url.toString().contains("lp"))
            if (!firstTime && frontPage.contains("recaptcha_image")) {
                System.out.println("Captcha detect");
                SwingUtilities.invokeLater(CaptchaSolver::initFX);
                while (key == null) {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                firstTime = true;

                return Collections.singletonMap("g-recaptcha-response", key);
            } else {
                System.out.println("Captcha detect, but skipping");
                firstTime = true;
                return Collections.emptyMap();
            }
        return Collections.emptyMap();
    }
}
