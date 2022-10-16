package com.supalosa.bot.debug;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.supalosa.bot.AgentData;
import com.supalosa.bot.SupaBot;
import com.supalosa.bot.analysis.Region;
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
    public static final double OUTPUT_SCALE_FACTOR = 4.0;

    private SupaBot agent;

    private JFrame frame;
    private JPanel panel;

    private long lastFrameUpdate = 0L;

    @Override
    public void initialise(SupaBot agent) {
        this.agent = agent;

        this.frame = new JFrame("SupaBot Debug");

        this.panel = new JPanel(new FlowLayout());
        frame.getContentPane().add(panel);

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        frame.pack();
        frame.setVisible(true);
    }

    @Override
    public void onStep(SupaBot agent, AgentData data) {
        if (!frame.isVisible()) {
            return;
        }
        long gameLoop = agent.observation().getGameLoop();
        if (gameLoop < lastFrameUpdate + 22L) {
            return;
        }
        if (data.mapAnalysis().isEmpty()) {
            return;
        }
        lastFrameUpdate = gameLoop;
        panel.removeAll();

        int mapHeight = data.mapAnalysis().get().getGrid().getHeight();
        float distanceToBorderMax = 20;
        BufferedImage baseBmp = VisualisationUtils.renderNewGrid(
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
        // Copy the base to new bitmaps
        BufferedImage placementBmp = new BufferedImage(baseBmp.getColorModel(), baseBmp.copyData(null), false, null);
        BufferedImage regionBmp = new BufferedImage(baseBmp.getColorModel(), baseBmp.copyData(null), false, null);

        data.structurePlacementCalculator().ifPresent(spc -> {
            VisualisationUtils.renderTileSet(
                    placementBmp,
                    spc.getMutableFreePlacementGrid(),
                    placeable -> placeable ? WHITE : RED,
                    (existingValue, newValue) -> newValue == RED ? newValue : existingValue);
        });
        double baseThreat = 50;
        data.mapAwareness().getAllRegionData().forEach(regionData -> {
            VisualisationUtils.renderTileSet(
                    regionBmp,
                    regionData.region(),
                    (_prevVal) -> {
                        if (regionData.isBlocked()) {
                            return VisualisationUtils.makeRgb(128, 128, 128);
                        }
                        double visibilityFactor = Math.min(1.0, 0.15 + (regionData.decayingVisibilityPercent() * 0.85));
                        double red = (255 - (int) (255 * regionData.playerThreat() / baseThreat)) * visibilityFactor;
                        double green = (255 - (int) (255 * regionData.enemyThreat() / baseThreat)) * visibilityFactor;
                        double blue = (255 - (int) (128 * regionData.playerThreat() / baseThreat)
                                        - (int) (127 * regionData.enemyThreat() / baseThreat)) * visibilityFactor;
                        return VisualisationUtils.makeRgb((int)red, (int)green, (int)blue);
                    },
                    (_prevVal) -> {
                        double red = 255;
                        double green = (255 - (int) (255 * regionData.diffuseEnemyThreat() / baseThreat));
                        double blue = (255 - (int) (255 * regionData.diffuseEnemyThreat() / baseThreat));
                        return VisualisationUtils.makeRgb((int)red, (int)green, (int)blue);
                    });
            /*

            if (regionData.diffuseEnemyThreat() > 0) {
                int x = scaleX(regionData.region().centrePoint().getX());
                int y = scaleY(regionData.region().centrePoint().getY(),  mapHeight);
                drawCross(g, x, y + 10, (int) (regionData.diffuseEnemyThreat()));
            }
             */
        });
        // scale the bitmap
        AffineTransform transform = new AffineTransform();
        transform.scale(OUTPUT_SCALE_FACTOR, OUTPUT_SCALE_FACTOR);
        AffineTransformOp transformOp = new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR);

        BufferedImage placementScaled = new BufferedImage((int)(placementBmp.getWidth() * OUTPUT_SCALE_FACTOR), (int)(baseBmp.getHeight() * OUTPUT_SCALE_FACTOR), BufferedImage.TYPE_3BYTE_BGR);
        QuickDrawPanel placementPanel = new QuickDrawPanel(transformOp.filter(placementBmp, placementScaled));

        BufferedImage pathingScaled = new BufferedImage((int)(regionBmp.getWidth() * OUTPUT_SCALE_FACTOR), (int)(baseBmp.getHeight() * OUTPUT_SCALE_FACTOR), BufferedImage.TYPE_3BYTE_BGR);
        QuickDrawPanel placementPanel2 = new QuickDrawPanel(transformOp.filter(regionBmp, pathingScaled));


        Graphics2D g = (Graphics2D) pathingScaled.getGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw other metadata for regions.
        data.mapAwareness().getAllRegionData().forEach(regionData -> {
            g.setColor(Color.RED);
            if (regionData.killzoneFactor() > 2.0) {
                int x = scaleX(regionData.region().centrePoint().getX());
                int y = scaleY(regionData.region().centrePoint().getY(),  mapHeight);
                drawCross(g, x, y, (int) (1 * regionData.killzoneFactor()));
            }
            g.setColor(Color.WHITE);
            int x = scaleX(regionData.region().centrePoint().getX());
            int y = scaleY(regionData.region().centrePoint().getY(), mapHeight);
            Font lastFont = g.getFont();
            g.setFont(lastFont.deriveFont(10.0f));
            g.drawString(Integer.toString(regionData.region().regionId()), x, y);
            g.setFont(lastFont);
        });

        // Draw army metadata.
        data.fightManager().getAllArmies().forEach(armyTask -> {
            armyTask.getCentreOfMass().ifPresent(centreOfMass -> {
                int x = scaleX(centreOfMass.getX());
                int y = scaleY(centreOfMass.getY(),  mapHeight);
                g.setColor(Color.GREEN);
                g.drawOval(x - 4, y - 4, 8, 8);
                g.setColor(Color.RED);
                armyTask.getWaypoints().ifPresent(waypoints -> {
                    if (waypoints.size() > 0) {
                        Point2d lastPoint = centreOfMass;
                        for (Region waypoint : waypoints) {
                            Point2d newPoint = waypoint.centrePoint();
                            drawArrowLine(g,
                                    scaleX(lastPoint.getX()), scaleY(lastPoint.getY(), mapHeight),
                                    scaleX(newPoint.getX()), scaleY(newPoint.getY(), mapHeight));
                            lastPoint = newPoint;
                        }
                        if (armyTask.getTargetPosition().isPresent()) {
                            Point2d targetPosition = armyTask.getTargetPosition().get();
                            drawArrowLine(g,
                                    scaleX(lastPoint.getX()), scaleY(lastPoint.getY(), mapHeight),
                                    scaleX(targetPosition.getX()), scaleY(targetPosition.getY(), mapHeight));
                        }
                    }
                });
            });
            armyTask.getTargetPosition().ifPresent(targetPosition -> {
                int x = (int)(targetPosition.getX() * OUTPUT_SCALE_FACTOR);
                int y = (int)((mapHeight - targetPosition.getY()) * OUTPUT_SCALE_FACTOR);
                g.setColor(Color.RED);
                g.drawOval(x - 4, y - 4, 8, 8);
            });
        });

        panel.add(placementPanel);
        panel.add(placementPanel2);

        frame.pack();
    }

    private int scaleX(int originalX) {
        return (int)(originalX * OUTPUT_SCALE_FACTOR);
    }

    private int scaleX(float originalX) {
        return (int)(originalX * OUTPUT_SCALE_FACTOR);
    }

    private int scaleY(int originalY, int mapHeight) {
        return (int)((mapHeight - originalY) * OUTPUT_SCALE_FACTOR);
    }

    private int scaleY(float originalY, int mapHeight) {
        return (int)((mapHeight - originalY) * OUTPUT_SCALE_FACTOR);
    }

    // https://stackoverflow.com/a/3094933
    private void drawArrowLine(Graphics2D graphics, int x1, int y1, int x2, int y2) {
        graphics.drawLine(x1, y1, x2, y2);
        double angle = Math.atan2(y2 - y1, x2 - x1);
        double angle1 = angle - Math.PI/1.5;
        double angle2 = angle + Math.PI/1.5;
        final double arrowLength = 5.0;
        graphics.drawLine(x2, y2, x2 + (int)(Math.cos(angle1) * arrowLength), y2 + (int)(Math.sin(angle1) * arrowLength));
        graphics.drawLine(x2, y2, x2 + (int)(Math.cos(angle2) * arrowLength), y2 + (int)(Math.sin(angle2) * arrowLength));
    }

    private void drawCross(Graphics2D graphics, int x, int y, int size) {
        Stroke oldStroke = graphics.getStroke();
        BasicStroke newStroke = new BasicStroke(3);
        graphics.setStroke(newStroke);
        graphics.drawLine(x - size, y - size, x + size, y + size);
        graphics.drawLine(x - size, y + size, x + size, y - size);
        graphics.setStroke(oldStroke);
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
