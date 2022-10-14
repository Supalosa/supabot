package com.supalosa.bot.debug;

import com.supalosa.bot.AgentData;
import com.supalosa.bot.SupaBot;
import com.supalosa.bot.analysis.utils.VisualisationUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;

public class JFrameDebugTarget implements DebugTarget {

    public static final int WHITE = VisualisationUtils.makeRgb(255, 255, 255);
    public static final int BLACK = VisualisationUtils.makeRgb(0, 0, 0);
    public static final int RED = VisualisationUtils.makeRgb(255, 0, 0);

    private SupaBot agent;

    private JFrame frame;

    private long lastFrameUpdate = 0L;

    @Override
    public void initialise(SupaBot agent) {
        this.agent = agent;

        frame = new JFrame("SupaBot Debug");

        frame.getContentPane().add(new JPanel(new FlowLayout()));

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        frame.pack();
        frame.setVisible(true);
    }

    @Override
    public void onStep(SupaBot agent, AgentData data) {
        long gameLoop = agent.observation().getGameLoop();
        if (gameLoop < lastFrameUpdate + 22L) {
            return;
        }
        if (data.mapAnalysis().isEmpty()) {
            return;
        }
        lastFrameUpdate = gameLoop;
        frame.getContentPane().removeAll();

        float distanceToBorderMax = 20;
        BufferedImage placementBmp = VisualisationUtils.renderNewGrid(
                data.mapAnalysis().get().getGrid(),
                tile -> {
                    if (!tile.pathable && !tile.placeable) {
                        return BLACK;
                    }
                    if (tile.distanceToBorder == 1) {
                        return WHITE;
                    }
                    float dbRatio = tile.distanceToBorder / distanceToBorderMax;
                    int colComponent = (int)(255 * dbRatio);
                    return VisualisationUtils.makeRgb(0, colComponent, colComponent);
                });

        data.structurePlacementCalculator().ifPresent(spc -> {
            VisualisationUtils.addToRenderedGrid(
                    placementBmp,
                    spc.getMutableFreePlacementGrid(),
                    placeable -> placeable ? WHITE : RED,
                    (existingValue, newValue) -> newValue == RED ? newValue : existingValue);

        });

        // scale the bitmap
        AffineTransform transform = new AffineTransform();
        transform.scale(2.0, 2.0);
        AffineTransformOp transformOp = new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR);

        BufferedImage after = new BufferedImage(placementBmp.getWidth() * 2, placementBmp.getHeight() * 2, BufferedImage.TYPE_3BYTE_BGR);
        QuickDrawPanel placementPanel = new QuickDrawPanel(transformOp.filter(placementBmp, after));
        frame.getContentPane().add(placementPanel);

        frame.pack();
    }

    @Override
    public void stop() {
        frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
    }

    // from https://coderanch.com/t/343676/java/Drawing-Bitmap-JFrame
    class QuickDrawPanel extends JPanel {
        BufferedImage image;
        Dimension size = new Dimension();

        public QuickDrawPanel() { }

        public QuickDrawPanel(BufferedImage image) {
            this.image = image;
            setComponentSize();
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.drawImage(image, 0, 0, this);
        }

        public Dimension getPreferredSize() {
            return size;
        }

        public void setImage(BufferedImage bi) {
            image = bi;
            setComponentSize();
            repaint();
        }

        private void setComponentSize() {
            if(image != null) {
                size.width  = image.getWidth();
                size.height = image.getHeight();
                revalidate();  // signal parent/scrollpane
            }
        }
    }
}
