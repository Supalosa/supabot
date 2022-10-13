package com.supalosa.bot.debug;

import com.supalosa.bot.AgentData;
import com.supalosa.bot.SupaBot;
import com.supalosa.bot.analysis.utils.VisualisationUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
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

        BufferedImage placementBmp = VisualisationUtils.renderNewGrid(
                data.mapAnalysis().get().getGrid(),
                tile -> tile.placeable ? WHITE : BLACK);

        data.structurePlacementCalculator().ifPresent(spc -> {
            frame.getContentPane().add(new JLabel("Placement Grid"));
            VisualisationUtils.addToRenderedGrid(
                    placementBmp,
                    spc.getMutableFreePlacementGrid(),
                    placeable -> placeable ? WHITE : RED,
                    (existingValue, newValue) -> newValue == RED ? newValue : existingValue);

        });

        QuickDrawPanel placementPanel = new QuickDrawPanel(placementBmp);
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
