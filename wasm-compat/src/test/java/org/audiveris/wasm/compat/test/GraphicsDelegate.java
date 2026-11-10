package org.audiveris.wasm.compat.test;

/** Generated JVM-only delegation; see dev/teavm-audit/GenerateGraphicsDelegate.java. */
abstract class GraphicsDelegate extends java.awt.Graphics2D {
    protected final java.awt.Graphics2D delegate;
    GraphicsDelegate(java.awt.Graphics2D delegate) { this.delegate = delegate; }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.awt.Color p9, java.awt.image.ImageObserver p10) { return delegate.drawImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.awt.image.ImageObserver p9) { return delegate.drawImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, java.awt.Color p5, java.awt.image.ImageObserver p6) { return delegate.drawImage(p0, p1, p2, p3, p4, p5, p6); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, int p3, int p4, java.awt.image.ImageObserver p5) { return delegate.drawImage(p0, p1, p2, p3, p4, p5); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, java.awt.Color p3, java.awt.image.ImageObserver p4) { return delegate.drawImage(p0, p1, p2, p3, p4); }
    @Override public boolean drawImage(java.awt.Image p0, int p1, int p2, java.awt.image.ImageObserver p3) { return delegate.drawImage(p0, p1, p2, p3); }
    @Override public boolean drawImage(java.awt.Image p0, java.awt.geom.AffineTransform p1, java.awt.image.ImageObserver p2) { return delegate.drawImage(p0, p1, p2); }
    @Override public boolean hit(java.awt.Rectangle p0, java.awt.Shape p1, boolean p2) { return delegate.hit(p0, p1, p2); }
    @Override public java.awt.Color getColor() { return delegate.getColor(); }
    @Override public java.awt.Color getBackground() { return delegate.getBackground(); }
    @Override public java.awt.Composite getComposite() { return delegate.getComposite(); }
    @Override public java.awt.Font getFont() { return delegate.getFont(); }
    @Override public java.awt.FontMetrics getFontMetrics(java.awt.Font p0) { return delegate.getFontMetrics(p0); }
    @Override public java.awt.Graphics create() { return delegate.create(); }
    @Override public java.awt.GraphicsConfiguration getDeviceConfiguration() { return delegate.getDeviceConfiguration(); }
    @Override public java.awt.Paint getPaint() { return delegate.getPaint(); }
    @Override public java.awt.Rectangle getClipBounds() { return delegate.getClipBounds(); }
    @Override public java.awt.RenderingHints getRenderingHints() { return delegate.getRenderingHints(); }
    @Override public java.awt.Shape getClip() { return delegate.getClip(); }
    @Override public java.awt.Stroke getStroke() { return delegate.getStroke(); }
    @Override public java.awt.font.FontRenderContext getFontRenderContext() { return delegate.getFontRenderContext(); }
    @Override public java.awt.geom.AffineTransform getTransform() { return delegate.getTransform(); }
    @Override public java.lang.Object getRenderingHint(java.awt.RenderingHints.Key p0) { return delegate.getRenderingHint(p0); }
    @Override public void clearRect(int p0, int p1, int p2, int p3) { delegate.clearRect(p0, p1, p2, p3); }
    @Override public void clipRect(int p0, int p1, int p2, int p3) { delegate.clipRect(p0, p1, p2, p3); }
    @Override public void copyArea(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.copyArea(p0, p1, p2, p3, p4, p5); }
    @Override public void dispose() { delegate.dispose(); }
    @Override public void drawArc(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.drawArc(p0, p1, p2, p3, p4, p5); }
    @Override public void drawLine(int p0, int p1, int p2, int p3) { delegate.drawLine(p0, p1, p2, p3); }
    @Override public void drawOval(int p0, int p1, int p2, int p3) { delegate.drawOval(p0, p1, p2, p3); }
    @Override public void drawPolygon(int[] p0, int[] p1, int p2) { delegate.drawPolygon(p0, p1, p2); }
    @Override public void drawPolyline(int[] p0, int[] p1, int p2) { delegate.drawPolyline(p0, p1, p2); }
    @Override public void drawRoundRect(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.drawRoundRect(p0, p1, p2, p3, p4, p5); }
    @Override public void fillArc(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.fillArc(p0, p1, p2, p3, p4, p5); }
    @Override public void fillOval(int p0, int p1, int p2, int p3) { delegate.fillOval(p0, p1, p2, p3); }
    @Override public void fillPolygon(int[] p0, int[] p1, int p2) { delegate.fillPolygon(p0, p1, p2); }
    @Override public void fillRect(int p0, int p1, int p2, int p3) { delegate.fillRect(p0, p1, p2, p3); }
    @Override public void fillRoundRect(int p0, int p1, int p2, int p3, int p4, int p5) { delegate.fillRoundRect(p0, p1, p2, p3, p4, p5); }
    @Override public void setClip(int p0, int p1, int p2, int p3) { delegate.setClip(p0, p1, p2, p3); }
    @Override public void setClip(java.awt.Shape p0) { delegate.setClip(p0); }
    @Override public void setColor(java.awt.Color p0) { delegate.setColor(p0); }
    @Override public void setFont(java.awt.Font p0) { delegate.setFont(p0); }
    @Override public void setPaintMode() { delegate.setPaintMode(); }
    @Override public void setXORMode(java.awt.Color p0) { delegate.setXORMode(p0); }
    @Override public void addRenderingHints(java.util.Map p0) { delegate.addRenderingHints(p0); }
    @Override public void clip(java.awt.Shape p0) { delegate.clip(p0); }
    @Override public void draw(java.awt.Shape p0) { delegate.draw(p0); }
    @Override public void drawGlyphVector(java.awt.font.GlyphVector p0, float p1, float p2) { delegate.drawGlyphVector(p0, p1, p2); }
    @Override public void drawImage(java.awt.image.BufferedImage p0, java.awt.image.BufferedImageOp p1, int p2, int p3) { delegate.drawImage(p0, p1, p2, p3); }
    @Override public void drawRenderableImage(java.awt.image.renderable.RenderableImage p0, java.awt.geom.AffineTransform p1) { delegate.drawRenderableImage(p0, p1); }
    @Override public void drawRenderedImage(java.awt.image.RenderedImage p0, java.awt.geom.AffineTransform p1) { delegate.drawRenderedImage(p0, p1); }
    @Override public void drawString(java.lang.String p0, float p1, float p2) { delegate.drawString(p0, p1, p2); }
    @Override public void drawString(java.lang.String p0, int p1, int p2) { delegate.drawString(p0, p1, p2); }
    @Override public void drawString(java.text.AttributedCharacterIterator p0, float p1, float p2) { delegate.drawString(p0, p1, p2); }
    @Override public void drawString(java.text.AttributedCharacterIterator p0, int p1, int p2) { delegate.drawString(p0, p1, p2); }
    @Override public void fill(java.awt.Shape p0) { delegate.fill(p0); }
    @Override public void rotate(double p0) { delegate.rotate(p0); }
    @Override public void rotate(double p0, double p1, double p2) { delegate.rotate(p0, p1, p2); }
    @Override public void scale(double p0, double p1) { delegate.scale(p0, p1); }
    @Override public void setBackground(java.awt.Color p0) { delegate.setBackground(p0); }
    @Override public void setComposite(java.awt.Composite p0) { delegate.setComposite(p0); }
    @Override public void setPaint(java.awt.Paint p0) { delegate.setPaint(p0); }
    @Override public void setRenderingHint(java.awt.RenderingHints.Key p0, java.lang.Object p1) { delegate.setRenderingHint(p0, p1); }
    @Override public void setRenderingHints(java.util.Map p0) { delegate.setRenderingHints(p0); }
    @Override public void setStroke(java.awt.Stroke p0) { delegate.setStroke(p0); }
    @Override public void setTransform(java.awt.geom.AffineTransform p0) { delegate.setTransform(p0); }
    @Override public void shear(double p0, double p1) { delegate.shear(p0, p1); }
    @Override public void transform(java.awt.geom.AffineTransform p0) { delegate.transform(p0); }
    @Override public void translate(double p0, double p1) { delegate.translate(p0, p1); }
    @Override public void translate(int p0, int p1) { delegate.translate(p0, p1); }
}
