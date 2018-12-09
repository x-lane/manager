package manager.ui.splash;


import codex.utils.ImageUtils;
import com.sixlegs.png.AnimatedPngImage;
import com.sixlegs.png.Animator;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.MatteBorder;


public final class SplashScreen extends JFrame {
    
    private JPanel infoPanel;
    private ImagePanel imagePanel;
    private Rectangle2D.Double progressArea;
    private Rectangle2D.Double descriptionArea;

    public SplashScreen(String path) {
        setUndecorated(true);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        
        
        imagePanel = new ImagePanel();
        imagePanel.setStretchMode(ImagePanel.STRETCH_PRESERVE);
        getContentPane().add(imagePanel, BorderLayout.CENTER);
        
        JLabel logo = new JLabel(ImageUtils.getByPath("/images/logo.png"));
        logo.setOpaque(true);
        logo.setBackground(new Color(255, 255, 255, 128));
        
        JPanel logoPanel = new JPanel();
        logoPanel.setOpaque(false);
        logoPanel.setLayout(new BoxLayout(logoPanel, BoxLayout.PAGE_AXIS));
        logoPanel.add(logo);

        imagePanel.add(logoPanel, BorderLayout.EAST);
        
        infoPanel = new JPanel();
        infoPanel.setBackground(Color.WHITE);
        getContentPane().add(infoPanel, BorderLayout.SOUTH);
        
        imagePanel.setBorder(new MatteBorder(1, 1, 0, 1, Color.LIGHT_GRAY));
        infoPanel.setBorder( new MatteBorder(0, 1, 1, 1, Color.LIGHT_GRAY));
        
        File file = new File(this.getClass().getClassLoader().getResource(path).getFile());
        try {
            AnimatedPngImage png = new AnimatedPngImage();
            png.read(file);
            Dimension size = new Dimension(png.getWidth(), png.getHeight());
            
            infoPanel.setPreferredSize(new Dimension(size.width, 35));
            imagePanel.setPreferredSize(size);
            BufferedImage[] frames = new AnimatedPngImage().readAllFrames(file);
                      
            if (png.isAnimated()) {
                final BufferedImage target = imagePanel.getGraphicsConfiguration().createCompatibleImage(
                        size.width, size.height,
                        Transparency.TRANSLUCENT
                );
                final Animator animator = new Animator(png, frames, target);
                Timer timer = new Timer(50, null);
                timer.setInitialDelay(0);
                timer.addActionListener(animator);
                
                timer.addActionListener((e) -> {
                    imagePanel.setImage(target);
                });
                timer.start();
            } else {
                imagePanel.setImage(frames[0]);
            }
            pack();
            setLocationRelativeTo(null);
            setVisible(true);
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        progressArea = new Rectangle2D.Double(
                0, infoPanel.getHeight()-10, 
                infoPanel.getWidth(), 3
        );
        descriptionArea = new Rectangle2D.Double(
                0, infoPanel.getHeight()-30,
                infoPanel.getWidth(), 20
        );
        SwingUtilities.invokeLater(() -> {
            setProgress(0);
        });
        pack();
    }
    
    public void setProgress(int progress, String text) {
        setProgress(progress);
        if (isVisible()) {
            Graphics2D g = (Graphics2D) infoPanel.getGraphics();
            g.setPaint(infoPanel.getBackground());
            g.fillRect(
                (int) descriptionArea.getX()+1,
                (int) descriptionArea.getY(),
                (int) descriptionArea.getWidth()-2,
                (int) descriptionArea.getHeight()
            );

            g.setPaint(Color.BLACK);
            g.drawString(
                    text, 
                    (int)(descriptionArea.getX()+5),
                    (int)(descriptionArea.getY()+g.getFontMetrics().getHeight())
            );
        }
        try { Thread.sleep(100); } catch (InterruptedException e) {}
    }
    
    public void setProgress(int progress) {
        if (isVisible()) {
            Graphics2D g = (Graphics2D) infoPanel.getGraphics();

            int x   = (int) progressArea.getMinX()+1;
            int y   = (int) progressArea.getMinY();
            int wid = (int) progressArea.getWidth()-2;
            int hgt = (int) progressArea.getHeight();
            
            g.setPaint(Color.LIGHT_GRAY);
            g.fillRect(x, y, wid, hgt);

            int doneWidth = Math.round(progress * wid / 100.f);
                doneWidth = Math.max(0, Math.min(doneWidth, wid));

            g.setPaint(Color.RED);
            g.fillRect(x, y, doneWidth, hgt);
        }
    }
    
}
