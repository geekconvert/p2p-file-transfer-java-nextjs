package p2p.service;

import p2p.utils.UploadUtils;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;

public class FileSharer {

    private HashMap<Integer, String> availableFiles;

    public FileSharer() {
        availableFiles = new HashMap<>();
    }

    public int offerFile(String filePath) {
        int port;
        while (true) {
            port = UploadUtils.generateCode();
            if (!availableFiles.containsKey(port)) {
                availableFiles.put(port, filePath);
                return port;
            }
        }
    }

    public void startFileServer(int port) {
        String filePath = availableFiles.get(port);
        if (filePath == null) {
            System.err.println("No file associated with port: " + port);
            return;
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Serving file '" + new File(filePath).getName() + "' on port " + port);
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connected: " + clientSocket.getInetAddress());

            new Thread(new FileSenderHandler(clientSocket, filePath)).start();

        } catch (IOException e) {
            System.err.println("Error starting file server on port " + port + ": " + e.getMessage());
        }
        /*
        This code creates a P2P file server that waits for a client to connect and then sends the file to them.

        ServerSocket serverSocket = new ServerSocket(port)
        - Creates a server socket that listens on the specified port (e.g., 12345)
        - Ready to accept incoming connections from downloaders.


        Socket clientSocket = serverSocket.accept();
        - BLOCKS here until a client connects
        - When DownloadHandler creates new Socket("localhost", port), this accepts it
        - Returns a Socket representing the connected client

        new Thread(new FileSenderHandler(clientSocket, filePath)).start();
        - Creates a new thread to handle sending the file
        - This allows the server to potentially handle another connection (though it exits after one)
        - FileSenderHandler reads the file and streams it to the client
        */
    }

    private static class FileSenderHandler implements Runnable {
        private final Socket clientSocket;
        private final String filePath;

        public FileSenderHandler(Socket clientSocket, String filePath) {
            this.clientSocket = clientSocket;
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try (FileInputStream fis = new FileInputStream(filePath);
                 OutputStream oss = clientSocket.getOutputStream()) {

                /*
                - fis reads the uploaded file from disk (e.g., /tmp/peerlink-uploads/uuid_document.pdf)
                - oss sends data through the socket to the downloader
                */
                
                // Send the filename as a header
                String filename = new File(filePath).getName();
                String header = "Filename: " + filename + "\n";
                oss.write(header.getBytes());

                /*
                - Extracts just the filename: "uuid_document.pdf"
                - Sends: "Filename: document.pdf\n" to the client
                - Client reads this first to know what the file is called
                */
                
                // Send the file content
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    oss.write(buffer, 0, bytesRead);
                }

                /*
                - Reads file in 4KB chunks
                - Writes each chunk directly to the socket
                - Continues until entire file is sent
                */
                System.out.println("File '" + filename + "' sent to " + clientSocket.getInetAddress());
            } catch (IOException e) {
                System.err.println("Error sending file to client: " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing client socket: " + e.getMessage());
                }
            }
        }
        /*
        This reads the file from disk and streams it to the connected client over the socket connection.

        - Try-with-resources auto-closes file and socket streams
        - Finally block ensures client socket is closed even if error occurs

        What the client receives:

       - Filename: document.pdf\n
       - [binary file bytes in 4KB chunks...]
        */
    }

}