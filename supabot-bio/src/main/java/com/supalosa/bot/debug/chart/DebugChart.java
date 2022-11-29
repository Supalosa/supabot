package com.supalosa.bot.debug.chart;

import io.vertx.codegen.doc.Tag;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.category.SlidingCategoryDataset;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.LinkedList;
import java.util.Map;

public class DebugChart extends JPanel {

    private DefaultCategoryDataset categoryDataset;
    private SlidingCategoryDataset categoryDatasetView;
    private final int maxValues;
    private LinkedList<String> rollingCategoryValues;
    private final ChartPanel chartPanel;

    public DebugChart(String title, String categoryAxisLabel, String valueAxisLabel, int maxValues) {
        super(new BorderLayout());

        this.maxValues = maxValues;
        this.categoryDataset = createDataset();
        this.categoryDatasetView = new SlidingCategoryDataset(this.categoryDataset, 0, this.maxValues);
        this.rollingCategoryValues = new LinkedList<>();

        JFreeChart lineChart = ChartFactory.createLineChart(
                title,
                categoryAxisLabel,
                valueAxisLabel,
                this.categoryDatasetView,
                PlotOrientation.VERTICAL,
                true, true, false);
        lineChart.getLegend().setPosition(RectangleEdge.RIGHT);

        chartPanel = new ChartPanel(lineChart);
        chartPanel.setPreferredSize(new java.awt.Dimension(300, 200));
        this.add(chartPanel, BorderLayout.CENTER);
    }

    public void addValues(Map<String, Double> values, String columnKey) {
        rollingCategoryValues.add(columnKey);
        values.forEach((rowKey, value) -> {
            categoryDataset.addValue(value, rowKey, columnKey);
        });
        if (rollingCategoryValues.size() > maxValues) {
            //String expiredKey = rollingCategoryValues.removeFirst();
            // TODO remove the SlidingCategoryDataset.
            // This is probably rather slow since it's backed by an array list.
            this.categoryDatasetView.setFirstCategoryIndex(0);
            this.categoryDataset.removeColumn(0);
            rollingCategoryValues.removeFirst();
        }
        this.remove(chartPanel);
        this.add(chartPanel);
        //chartPanel.repaint();
        this.repaint();
        this.revalidate();
    }

    private DefaultCategoryDataset createDataset() {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        return dataset;
    }
}