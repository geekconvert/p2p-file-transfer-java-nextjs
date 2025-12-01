package p2p;

import java.io.IOException;

import p2p.controller.FileController;

/**
 * Hello world!
 */
public class App {
    public static void main(String[] args) {
        try {
            // Start the API server on port 8080
            FileController fileController = new FileController(8080);
            fileController.start();
            
            System.out.println("PeerLink server started on port 8080");
            System.out.println("UI available at http://localhost:3000");
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                fileController.stop();
            }));
            /*
            Registers a shutdown hook - code that runs when the JVM is shutting down
            Triggered when:
            - User presses Ctrl+C
            - System signals termination
            - JVM exits normally

            Ensures fileController.stop() is called to:
            - Close the HTTP server
            - Shutdown thread pool
            - Clean up resources
            */
            
            System.out.println("Press Enter to stop the server");
            System.in.read();
            /*
            - Blocks the main thread - waits for Enter key press
            - Prevents the program from exiting immediately
            - Keeps the server running in the background
            - When user presses Enter → System.in.read() returns → program exits → shutdown hook executes
            */
            
        } catch (IOException e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
