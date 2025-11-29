package SpamDetector;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.concurrent.TimeUnit;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Robust Client with Spam Filter (Outgoing only).
 * - Implements a toggle button for the spam filter.
 * - Uses the SpamFilter bridge to classify outgoing messages before sending.
 * - Blocks messages identified as high-confidence spam locally.
 */
public class Client {

    // ========== CONFIG (Matching Server's Python bridge configuration) ==========
    private static final String PYTHON_CMD = "python";
    private static final String PREDICT_SCRIPT = "predict.py";
    private static final double SPAM_CONF_THRESHOLD = 0.80; // block if confidence >= threshold

    private final JFrame frame = new JFrame("Client Chat - Farwah");
    private final JPanel messagesPanel = new JPanel();
    private final JScrollPane scrollPane;
    private final JTextField messageField = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JButton toggleSpamBtn = new JButton("Spam Filter: ON"); // New button

    private Socket socket;
    private DataInputStream din;
    private DataOutputStream dout;

    private final String host;
    private final int port;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private static final int MAX_MESSAGES = 500;
    private final DefaultCaretEnforcer caretEnforcer;

    // State
    private volatile boolean spamFilterEnabled = true; // Client's local filter state

    public Client(String host, int port) {
        this.host = host;
        this.port = port;

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(480, 780); // Adjusted size to match Server's new size
        frame.setLocation(800, 50);
        frame.setUndecorated(true);
        frame.getContentPane().setBackground(Color.WHITE);
        frame.setLayout(null);

        // Header (Adjusted bounds to match Server's header height)
        JPanel header = new JPanel(null);
        header.setBackground(new Color(7, 94, 84));
        header.setBounds(0, 0, frame.getWidth(), 80);
        frame.add(header);
        
        // Profile icon (Picture)
        JLabel profile = loadIconLabel("farwah.jpg", 54, 54);
        if (profile == null) {
            profile = new JLabel("P"); profile.setHorizontalAlignment(SwingConstants.CENTER);
            profile.setOpaque(true); profile.setBackground(Color.WHITE);
        }
        profile.setBounds(12, 12, 54, 54);
        header.add(profile);

        JLabel name = new JLabel("Farwah");
        name.setBounds(80, 18, 200, 22);
        name.setForeground(Color.WHITE);
        name.setFont(new Font("SansSerif", Font.BOLD, 18));
        header.add(name);

        JLabel status = new JLabel("Online");
        status.setBounds(80, 40, 200, 18);
        status.setForeground(Color.WHITE);
        status.setFont(new Font("SansSerif", Font.PLAIN, 12));
        header.add(status);

        // Spam toggle button (New)
        toggleSpamBtn.setBounds(frame.getWidth() - 170, 25, 120, 30);
        toggleSpamBtn.setFocusable(false);
        toggleSpamBtn.addActionListener(e -> toggleSpamFilter());
        header.add(toggleSpamBtn);

        // Back / close icon placeholder (just a button here)
        JButton closeBtn = new JButton("✕");
        closeBtn.setBounds(frame.getWidth() - 40, 20, 30, 30);
        closeBtn.setFocusable(false);
        closeBtn.setBackground(new Color(7,94,84));
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setBorderPainted(false);
        closeBtn.addActionListener(e -> shutdown());
        header.add(closeBtn);
        
        // messagesPanel setup
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(Color.WHITE);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(Color.WHITE);
        holder.add(messagesPanel, BorderLayout.NORTH);

        scrollPane = new JScrollPane(holder);
        scrollPane.setBounds(10, 90, frame.getWidth() - 30, 610); // Adjusted height
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        frame.add(scrollPane);

        // input area (Adjusted bounds to fit new frame size)
        messageField.setBounds(10, 710, frame.getWidth() - 140, 46);
        messageField.setFont(new Font("SansSerif", Font.PLAIN, 14));
        frame.add(messageField);

        sendButton.setBounds(frame.getWidth() - 115, 710, 105, 46);
        sendButton.setBackground(new Color(7, 94, 84));
        sendButton.setForeground(Color.WHITE);
        sendButton.setBorderPainted(false);
        frame.add(sendButton);

        caretEnforcer = new DefaultCaretEnforcer(scrollPane);

        sendButton.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());

        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        frame.setVisible(true);

        // Connect to server and start read loop (on the executor thread)
        connect();
    }

    // ---------------- UI / message helpers ----------------

    private void toggleSpamFilter() {
        spamFilterEnabled = !spamFilterEnabled;
        toggleSpamBtn.setText("Spam Filter: " + (spamFilterEnabled ? "ON" : "OFF"));
        // FIX: Pass null for classification
        appendMessage(formatMessagePanel("Spam filter turned " + (spamFilterEnabled ? "ON" : "OFF") + " (Local)", true, false, null));
    }
    
    // User sends a message from Client UI
    private void sendMessage() {
        String out = messageField.getText();
        if (out == null || out.trim().isEmpty()) return;

        // 1. Check for spam locally
        SpamFilter.Result res = SpamFilter.classifyIfEnabled(out, spamFilterEnabled);
        
        String classification = (res != null) ? 
            String.format("%s (%.2f)", res.label, res.confidence) : null;
        
        boolean isSpamAndBlock = (res != null && "spam".equals(res.label) && res.confidence >= SPAM_CONF_THRESHOLD);
        
        if (isSpamAndBlock) {
            // BLOCKED: Show in UI as blocked (right, red) and do NOT send
            appendMessage(formatMessagePanel("[BLOCKED SPAM - Outgoing] " + out, true, true, classification));
            messageField.setText("");
            // Notify server (so other clients see a blocked-notification)
            executor.submit(() -> {
                if (dout != null) {
                    try {
                        synchronized (dout) {
                            // send a short notification — do NOT include blocked content
                            dout.writeUTF("[BLOCKED SPAM - Outgoing from Client]");
                            dout.flush();
                        }
                    } catch (IOException e) {
                        appendMessage(formatMessagePanel("Notify failed: " + e.getMessage(), false, false, null));
                    }
                }
            });
            return;
        }
        
        // 2. Not blocked: Show locally as sent (right-side)
        appendMessage(formatMessagePanel(out, true, false, classification));

        // 3. Send to server off-EDT
        executor.submit(() -> {
            if (dout != null) {
                // ... (rest of networking code is the same)
                try {
                    synchronized (dout) {
                        dout.writeUTF(out);
                        dout.flush();
                    }
                } catch (IOException e) {
                    // FIX: Pass null for classification
                    appendMessage(formatMessagePanel("Send failed: " + e.getMessage(), false, false, null));
                }
            } else {
                // FIX: Pass null for classification
                appendMessage(formatMessagePanel("Not connected to server.", false, false, null));
            }
        });

        messageField.setText("");
    }

    private void appendMessage(JPanel panel) {
        SwingUtilities.invokeLater(() -> {
            if (messagesPanel.getComponentCount() / 2 > MAX_MESSAGES) {
                if (messagesPanel.getComponentCount() > 0) messagesPanel.remove(0);
                if (messagesPanel.getComponentCount() > 0) messagesPanel.remove(0);
            }
            messagesPanel.add(panel);
            messagesPanel.add(Box.createVerticalStrut(8)); // Consistent with Server
            messagesPanel.revalidate();
            messagesPanel.repaint();
            caretEnforcer.scrollToBottom();
        });
    }

    /**
     * Create a message bubble panel.
     *
     * @param text message text
     * @param sentByClient true -> right aligned (client), false -> left aligned (server)
     * @param blocked true -> render as blocked spam (red)
     * @param classification Optional result from spam classification (label|confidence)
     */
    private JPanel formatMessagePanel(String text, boolean sentByClient, boolean blocked, String classification) {
        // 1. Message Content
        JLabel label = new JLabel("<html>" + escapeHtml(text).replaceAll("\n", "<br>") + "</html>");
        label.setFont(new Font("Tahoma", Font.PLAIN, 15));
        label.setOpaque(true);
        label.setBorder(new EmptyBorder(10, 12, 10, 12));
        label.setMaximumSize(new Dimension(320, Integer.MAX_VALUE));
        
        Color bubbleColor, textColor;
        if (blocked) {
            bubbleColor = new Color(220, 40, 40); // red for blocked
            textColor = Color.WHITE;
        } else {
            bubbleColor = sentByClient ? new Color(37, 211, 102) : new Color(236, 229, 221);
            textColor = Color.BLACK;
        }
        label.setBackground(bubbleColor);
        label.setForeground(textColor);

        // 2. Time Stamp
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm");
        JLabel time = new JLabel(sdf.format(cal.getTime()));
        time.setFont(new Font("Tahoma", Font.PLAIN, 10));
        time.setForeground(Color.GRAY);

        // 3. Optional Spam/Ham Classification Info (NEW)
        JLabel classificationLabel = null;
        if (classification != null) {
            classificationLabel = new JLabel(classification);
            classificationLabel.setFont(new Font("Tahoma", Font.ITALIC, 10));
            classificationLabel.setForeground(blocked ? Color.LIGHT_GRAY : Color.DARK_GRAY);
            classificationLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
            classificationLabel.setBorder(new EmptyBorder(0, 12, 0, 12)); // Match padding
        }
        
        // 4. Inner Panel (Message + Classification Stacked)
        JPanel bubbleContent = new JPanel();
        bubbleContent.setLayout(new BoxLayout(bubbleContent, BoxLayout.Y_AXIS));
        bubbleContent.setBackground(bubbleColor);
        
        label.setAlignmentX(Component.RIGHT_ALIGNMENT);
        
        // Add message label
        bubbleContent.add(label);
        
        // Add classification info
        if (classificationLabel != null) {
             bubbleContent.add(Box.createVerticalStrut(2)); 
             bubbleContent.add(classificationLabel);
        }
        
        bubbleContent.setMaximumSize(bubbleContent.getPreferredSize());


        // 5. Outer Panel (Positioning the Bubble Left/Right)
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        
        JPanel timePanel = new JPanel(new BorderLayout());
        timePanel.setBackground(Color.WHITE);
        
        if (sentByClient) {
            panel.add(bubbleContent, BorderLayout.LINE_END);
            timePanel.add(time, BorderLayout.LINE_END);
        } else {
            // Note: Clients don't run spam filters on incoming server messages, 
            // but we use the general bubble color
            panel.add(bubbleContent, BorderLayout.LINE_START);
            timePanel.add(time, BorderLayout.LINE_START);
        }
        
        // 6. Overall Vertical Stack (Message Row + Time Row)
        JPanel outerStack = new JPanel();
        outerStack.setLayout(new BoxLayout(outerStack, BoxLayout.Y_AXIS));
        outerStack.setBackground(Color.WHITE);
        outerStack.add(panel);
        outerStack.add(timePanel);
        
        return outerStack;
    }

    // Keep the two-parameter convenience method (formatMessagePanel(String, boolean))
    // FIX: This now acts as a convenience wrapper calling the new four-parameter version
    private JPanel formatMessagePanel(String text, boolean sentByClient) {
        return formatMessagePanel(text, sentByClient, false, null);
    }
    
    // ----------------- Networking / Connection -----------------
    
    private void connect() {
        executor.submit(() -> {
            try {
                socket = new Socket(host, port);
                din = new DataInputStream(socket.getInputStream());
                dout = new DataOutputStream(socket.getOutputStream());

                // FIX: Pass null for classification
                appendMessage(formatMessagePanel("Connected to server: " + host + ":" + port, false, false, null));

                // Persistent read loop: continuously read messages from server
                while (!socket.isClosed()) {
                    String msg;
                    try {
                        msg = din.readUTF(); // This thread blocks here waiting for data
                    } catch (EOFException | SocketException ex) {
                        break; // Server closed or network error
                    }
                    if (msg == null) break;

                    // Show received message on client UI
                    // If server sent a blocked-outgoing notification, render it red (blocked)
                    boolean blockedNotification = (msg != null && msg.startsWith("[BLOCKED SPAM - Outgoing"));
                    appendMessage(formatMessagePanel(msg, false, blockedNotification, null));
                }
            } catch (IOException e) {
                // FIX: Pass null for classification
                appendMessage(formatMessagePanel("Connection failed: " + e.getMessage(), false, false, null));
            } finally {
                // FIX: Pass null for classification
                appendMessage(formatMessagePanel("Disconnected from server", false, false, null));
                closeResources();
            }
        });
    }

    private void closeResources() {
        try { if (din != null) din.close(); } catch (IOException ignored) {}
        try { if (dout != null) dout.close(); } catch (IOException ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }

    private void shutdown() {
        closeResources();
        executor.shutdownNow();
        frame.dispose();
        System.exit(0);
    }
    
    // ----------------- Spam Filter Bridge (Copied from Server) -----------------

    public static class SpamFilter {
        public static class Result {
            public final String label;
            public final double confidence;
            public Result(String label, double confidence) {
                this.label = label;
                this.confidence = confidence;
            }
        }

        public static Result classifyIfEnabled(String message, boolean enabled) {
            if (!enabled) return null;
            return classify(message);
        }

        private static Result classify(String message) {
            try {
                ProcessBuilder pb = new ProcessBuilder(PYTHON_CMD, PREDICT_SCRIPT, message);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = r.readLine();
                try { if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); } } catch (InterruptedException ignored) {}

                if (line != null) {
                    String[] parts = line.trim().split("\\|");
                    if (parts.length >= 2) {
                        String label = parts[0].trim();
                        double conf = 0.0;
                        try { conf = Double.parseDouble(parts[1]); } catch (NumberFormatException ignore) {}
                        return new Result(label, conf);
                    } else {
                        return new Result(line.trim(), 1.0);
                    }
                }
            } catch (IOException e) {
                System.err.println("Client SpamFilter error: " + e.getMessage());
            }
            return new Result("error", 0.0);
        }
    }
    
    // ----------------- Utilities (Copied from Server) -----------------

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static class DefaultCaretEnforcer {
        private final JScrollPane scrollPane;
        DefaultCaretEnforcer(JScrollPane sp) { this.scrollPane = sp; }
        void scrollToBottom() {
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        }
    }

    private JLabel loadIconLabel(String resourceName, int w, int h) {
        try {
            // Try classpath resource first
            java.net.URL url = ClassLoader.getSystemResource(resourceName);
            java.awt.image.BufferedImage img = null;
            if (url != null) {
                img = javax.imageio.ImageIO.read(url);
            } else {
                // Fall back to filesystem (project folder)
                java.io.File f = new java.io.File(resourceName);
                if (f.exists()) {
                    img = javax.imageio.ImageIO.read(f);
                }
            }
            if (img == null) return null;
            Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            return new JLabel(new ImageIcon(scaled));
        } catch (Throwable t) {
            return null;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Client("127.0.0.1", 6001));
    }
}