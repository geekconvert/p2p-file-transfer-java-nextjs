package p2p.controller;

import p2p.service.FileSharer;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.Socket;

import org.apache.commons.io.IOUtils;

public class FileController {
    private final FileSharer fileSharer;
    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;


    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();

        //Create HTTP server - HttpServer.create(new InetSocketAddress(port), 0) creates a basic HTTP server bound to the specified port.
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        /*
        Set upload directory - System.getProperty("java.io.tmpdir") gets the system's temporary directory (e.g., tmp on macOS/Linux, C:\Temp on Windows), then appends /peerlink-uploads to create a dedicated folder for uploaded files.
        */
        this.uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "peerlink-uploads";
        this.executorService = Executors.newFixedThreadPool(10);
        
        //Create upload directory if missing - Checks if the peerlink-uploads folder exists, and if not, creates it (including any parent directories with mkdirs()).
        File uploadDirFile = new File(uploadDir);
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdirs();
        }
        
        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/", new CORSHandler());
        /*
        Register HTTP endpoints - Creates three routes:

        /upload → handled by UploadHandler (receives file uploads)
        /download → handled by DownloadHandler (retrieves files from peers)
        / → handled by CORSHandler (handles CORS and catches unmatched routes)
        */
        
        server.setExecutor(executorService);
        // Set thread pool executor - server.setExecutor(executorService) assigns the 10-thread executor pool to handle incoming HTTP requests concurrently, allowing the server to process multiple requests simultaneously.
    }
    
    public void start() {
        server.start();
        System.out.println("API server started on port " + server.getAddress().getPort());
    }
    
    public void stop() {
        server.stop(0);
        executorService.shutdown();
        System.out.println("API server stopped");
    }
    
    private class CORSHandler implements HttpHandler {

        /*
        This CORSHandler handles Cross-Origin Resource Sharing (CORS) to allow your Next.js frontend (running on a different port) to communicate with your Java backend.

        Purpose: This handler is registered at the root context / to catch any requests that don't match /upload or /download routes. It ensures CORS headers are set and handles preflight checks, while returning 404 for any unmapped endpoints.

        How it works:
        - When the UI sends a request to /upload, it goes directly to UploadHandler
        - UploadHandler adds the Access-Control-Allow-Origin: * header to the response
        - The browser receives this header and allows the cross-origin request

        The CORSHandler at / only catches:
        - OPTIONS preflight requests to the root path
        - Any unmatched routes (returns 404)

        A preflight request is an automatic OPTIONS request that browsers send before making certain cross-origin requests to check if the server allows it.

        Browsers send preflight requests when your frontend (on http://localhost:3000) tries to make a request to your backend (on http://localhost:8080) that:
        - Uses methods other than GET/POST (like PUT, DELETE)
        - Sends custom headers (like Authorization)
        - Uses POST with non-standard content types (like application/json)

        The flow:
        - Browser sends OPTIONS request first (preflight):
            OPTIONS /upload HTTP/1.1
            Origin: http://localhost:3000
            Access-Control-Request-Method: POST
            Access-Control-Request-Headers: Content-Type
        - Server responds with CORS permissions:
            HTTP/1.1 204 No Content
            Access-Control-Allow-Origin: *
            Access-Control-Allow-Methods: GET, POST, OPTIONS
            Access-Control-Allow-Headers: Content-Type

        In our code: The CORSHandler handles OPTIONS requests to / (root path) only. If the browser sends a preflight to /upload, UploadHandler will reject it with "405 Method Not Allowed" because it only accepts POST, not OPTIONS.
        */
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();

            /*
            Set CORS headers - Adds headers to allow requests from any origin (*):

            Access-Control-Allow-Origin: * - Permits requests from any domain
            Access-Control-Allow-Methods - Specifies allowed HTTP methods (GET, POST, OPTIONS)
            Access-Control-Allow-Headers - Specifies which headers the client can send
            */
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            
            /*
            Handle preflight requests - Browsers send an OPTIONS request before actual requests to check if CORS is allowed:
            - If the request method is OPTIONS, respond with 204 No Content and exit
            - This tells the browser "CORS is permitted, proceed with the actual request"

            When you pass -1 as the response length:
            - The server won't send a Content-Length header
            - No response body is expected or sent
            - The response consists only of the status line and headers
            */
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1); //-1 means no response body will be sent
                return;
            }
            
            /*
            Fallback for unmatched routes - For any other request to the root path /:
            - Return a 404 Not Found response
            - Write "Not Found" to the response body
            */
            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }
    
    private static class MultipartParser {
        private final byte[] data;
        private final String boundary;
        
        /*
        example of the byte[] data:
        
        ------WebKitFormBoundaryABC123XYZ\r\n
        Content-Disposition: form-data; name="file"; filename="document.pdf"\r\n
        Content-Type: application/pdf\r\n
        \r\n
        %PDF-1.4
        %âãÏÓ
        1 0 obj
        <<
        /Type /Catalog
        /Pages 2 0 R
        >>
        endobj
        [... actual PDF binary data continues ...]
        \r\n
        ------WebKitFormBoundaryABC123XYZ--\r\n
        */
        public MultipartParser(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }
        
        public ParseResult parse() {
            try {
                String dataAsString = new String(data);
                
                String filenameMarker = "filename=\"";
                int filenameStart = dataAsString.indexOf(filenameMarker);
                if (filenameStart == -1) {
                    return null;
                }
                
                filenameStart += filenameMarker.length();
                int filenameEnd = dataAsString.indexOf("\"", filenameStart);
                String filename = dataAsString.substring(filenameStart, filenameEnd);
                
                String contentTypeMarker = "Content-Type: ";
                int contentTypeStart = dataAsString.indexOf(contentTypeMarker, filenameEnd);
                String contentType = "application/octet-stream"; // Default
                
                if (contentTypeStart != -1) {
                    contentTypeStart += contentTypeMarker.length();
                    int contentTypeEnd = dataAsString.indexOf("\r\n", contentTypeStart);
                    contentType = dataAsString.substring(contentTypeStart, contentTypeEnd);
                }
                
                String headerEndMarker = "\r\n\r\n";
                int headerEnd = dataAsString.indexOf(headerEndMarker);
                if (headerEnd == -1) {
                    return null;
                }
                
                int contentStart = headerEnd + headerEndMarker.length();
                
                byte[] boundaryBytes = ("\r\n--" + boundary + "--").getBytes();
                int contentEnd = findSequence(data, boundaryBytes, contentStart);
                
                if (contentEnd == -1) {
                    boundaryBytes = ("\r\n--" + boundary).getBytes();
                    contentEnd = findSequence(data, boundaryBytes, contentStart);
                }
                
                if (contentEnd == -1 || contentEnd <= contentStart) {
                    return null;
                }
                
                byte[] fileContent = new byte[contentEnd - contentStart];
                System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);
                
                return new ParseResult(filename, contentType, fileContent);
            } catch (Exception e) {
                System.err.println("Error parsing multipart data: " + e.getMessage());
                return null;
            }
        }
        
        private int findSequence(byte[] data, byte[] sequence, int startPos) {
            outer:
            for (int i = startPos; i <= data.length - sequence.length; i++) {
                for (int j = 0; j < sequence.length; j++) {
                    if (data[i + j] != sequence[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
        
        public static class ParseResult {
            public final String filename;
            public final String contentType;
            public final byte[] fileContent;
            
            public ParseResult(String filename, String contentType, byte[] fileContent) {
                this.filename = filename;
                this.contentType = contentType;
                this.fileContent = fileContent;
            }
        }
    }
    
    /* 
    CORS is handled within each handler (UploadHandler and DownloadHandler),
    */
    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            //exchange.getResponseHeaders() doesn't send the response - it just prepares the headers that will be sent later. This retrieves a mutable Headers object that you can modify Nothing is sent to the client yet you're just building up the headers that will be sent
            headers.add("Access-Control-Allow-Origin", "*");
            
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                //This is when headers are actually sent to the client
                /*
                This is try-with-resources syntax for automatic resource management. Here's what's happening: "exchange.getResponseBody()" returns an OutputStream that you can write to - it doesn't return existing content, it gives you a stream to send the response body to the client.

                OutputStream os = exchange.getResponseBody()
                This creates a connection to write data back to the client
                Nothing is returned from the client - you're getting a stream to the client


                os.write(response.getBytes())
                Converts the string "Method Not Allowed" to bytes. Sends those bytes to the client as the response body

                // try-with-resources automatically closes the stream. The stream is automatically closed and flushed. This completes the HTTP response
                */
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            
            Headers requestHeaders = exchange.getRequestHeaders();
            String contentType = requestHeaders.getFirst("Content-Type");
            /*
            getFirst("Content-Type") retrieves the first value of the Content-Type header from the HTTP request sent by the client. HTTP headers can have multiple values for the same header name.

            For file uploads, this would be something like: "multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW"

            What the Next.js UI sends:
            POST /upload HTTP/1.1
            Host: localhost:8080
            Content-Type: multipart/form-data; boundary=----WebKitFormBoundary...
            Content-Length: 12345

            [file data...]
            */

            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                String response = "Bad Request: Content-Type must be multipart/form-data";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            
            try {
                /* 
                This extracts the boundary string from the Content-Type header, which is used to separate different parts of the multipart form data.
                contentType.substring(31) - returns something like: "----WebKitFormBoundary7MA4YWxkTrZu0gW"

                boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"

                Why is this needed?: Multipart form data uses boundaries to separate file content from 
                metadata:

                ------WebKitFormBoundary7MA4YWxkTrZu0gW
                Content-Disposition: form-data; name="file"; filename="example.pdf"
                Content-Type: application/pdf

                [binary file data here]
                ------WebKitFormBoundary7MA4YWxkTrZu0gW--


                The parser needs this boundary string to split the data correctly and extract the filename and file content.
                */
                String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
                

                /*
                This code reads the entire request body (the uploaded file data) from the HTTP request and stores it in memory as a byte array.

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                - Creates an in-memory buffer to collect bytes
                - It automatically grows as you write data to it

                IOUtils.copy(exchange.getRequestBody(), baos);
                - exchange.getRequestBody() returns an InputStream containing the uploaded file data sent by Next.js
                - IOUtils.copy() reads all bytes from the input stream and writes them to baos
                - This reads the entire multipart form data (headers + file content)

                byte[] requestData = baos.toByteArray();
                - Converts the buffer contents into a byte array
                - Now you have all the uploaded data in memory ready to parse

                requestData contains the raw bytes of the entire HTTP request body sent from your Next.js UI, which will then be parsed.


                requestData contains both the multipart headers AND the file content:
                ------WebKitFormBoundaryABC123
                Content-Disposition: form-data; name="file"; filename="document.pdf"
                Content-Type: application/pdf

                [binary file bytes here...]
                ------WebKitFormBoundaryABC123--


                It includes:
                - Boundary markers - ------WebKitFormBoundaryABC123
                - Multipart headers - Content-Disposition, Content-Type
                - File content - The actual binary data of the uploaded file
                - Closing boundary - ------WebKitFormBoundaryABC123--


                requestData does NOT contain the HTTP Content-Type header.
                HTTP Headers:
                Content-Type: multipart/form-data; boundary=----WebKit...
                Content-Length: 12345
                
                [blank line separating headers from body]

                Request Body (this is what requestData contains):
                ------WebKitFormBoundaryABC123
                Content-Disposition: form-data; name="file"; filename="document.pdf"
                Content-Type: application/pdf
                
                [file binary data]
                ------WebKitFormBoundaryABC123--


                HTTP requests and responses both follow the same two-part structure. This is the HTTP protocol standard - all HTTP communication is split into headers (metadata) and body (actual content), separated by a blank line.


                Content-Length includes the ENTIRE request body - not just the file, but also all the multipart formatting (boundaries, Content-Disposition, etc.).


                Content-Length tells the server how many bytes to read from the network stream for the request body. It needs to include everything after the blank line that separates HTTP headers from the body - which includes all the multipart structure, not just the raw file data.
                */
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                IOUtils.copy(exchange.getRequestBody(), baos);
                byte[] requestData = baos.toByteArray();
                
                MultipartParser parser = new MultipartParser(requestData, boundary);
                MultipartParser.ParseResult result = parser.parse();
                
                if (result == null) {
                    String response = "Bad Request: Could not parse file content";
                    exchange.sendResponseHeaders(400, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    return;
                }
                
                String filename = result.filename;
                if (filename == null || filename.trim().isEmpty()) {
                    filename = "unnamed-file";
                }
                
                /*
                new File(filename).getName() extracts just the filename from a path, removing any directory components.

                filename = "document.pdf"
                new File(filename).getName() → "document.pdf"

                filename = "/path/to/document.pdf"
                new File(filename).getName() → "document.pdf"
                
                Why is this needed here?: This is a security measure to prevent path traversal attacks. A malicious user could upload a file with a name like: "../../../etc/passwd". Without getName(), this would try to write outside the upload directory. With getName(), it safely becomes just "passwd".
                */
                String uniqueFilename = UUID.randomUUID().toString() + "_" + new File(filename).getName();
                String filePath = uploadDir + File.separator + uniqueFilename;
                
                try (FileOutputStream fos = new FileOutputStream(filePath)) {
                    fos.write(result.fileContent);
                }
                /*
                This code saves the uploaded file to disk in the temporary upload directory.

                FileOutputStream fos = new FileOutputStream(filePath)
                - Creates (or overwrites) a file at filePath
                - Example path: /tmp/peerlink-uploads/a3f2b1c4-5d6e-7f8a-9b0c_document.pdf

                fos.write(result.fileContent);      
                - result.fileContent contains the pure file bytes (extracted by the parser, no multipart headers/boundaries)
                - Writes all those bytes to the file on disk


                // try-with-resources automatically closes the stream
                - Ensures the file is properly closed and flushed
                - Even if an error occurs, the file will be closed


                The uploaded file is now saved to disk at the location specified by filePath, and this is the file that will be shared via P2P when another user requests it using the port number.


                new FileOutputStream(filePath):
                - If the file doesn't exist → Creates a new empty file at that path
                - If the file already exists → Overwrites it (truncates to 0 bytes)
                */
                
                int port = fileSharer.offerFile(filePath);
                
                /* 
                This starts a P2P file server in a separate background thread to allow other peers to download the file.
                
                Why use a separate thread?:
                - startFileServer(port) is a blocking operation - it starts a server that waits for incoming connections. If you run it on the main thread, the upload handler would hang and never send the response back to the Next.js UI.

                Main Thread                          Background Thread
                -----------                          -----------------
                Upload file ✓
                Save to disk ✓
                Get port number ✓
                Start new thread ----creates----->  startFileServer(port)
                                                    [Waits for download requests]
                                                    [Keeps running...]
                Send response to UI ✓
                Continue handling requests ✓

                */
                new Thread(() -> fileSharer.startFileServer(port)).start();
                
                String jsonResponse = "{\"port\": " + port + "}";
                headers.add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(jsonResponse.getBytes());
                }
                
            } catch (Exception e) {
                System.err.println("Error processing file upload: " + e.getMessage());
                String response = "Server error: " + e.getMessage();
                exchange.sendResponseHeaders(500, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }
    }
    
    /* 
    CORS is handled within each handler (UploadHandler and DownloadHandler),
    */
    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            
            String path = exchange.getRequestURI().getPath();
            String portStr = path.substring(path.lastIndexOf('/') + 1);
            
            try {
                int port = Integer.parseInt(portStr);
                
                /*
                
                new Socket("localhost", port) - Creates a TCP socket connection
                - "localhost" - Connects to the same machine (127.0.0.1)
                - port - The port number where the P2P file server is running (e.g., 12345)
                - This establishes a connection to the background thread server started earlier

                socket.getInputStream() - Gets an input stream to read data from the socket
                - Returns an InputStream to receive bytes from the P2P server
                - This is how you read the file data being sent by the peer

                Try-with-resources - Both socket and socketInput are auto-closed when done

                Difference between ServerSocket and Socket:
                ServerSocket:
                - Server-side - Listens for incoming connections
                - Waits for clients to connect to it
                - Binds to a port and accepts connections
                - Example: ServerSocket server = new ServerSocket(12345);
                - Usage: Socket clientSocket = server.accept(); (blocks until client connects)
                
                Socket:
                - Client-side - Initiates connection to a server
                - Connects to an existing ServerSocket
                - Example: Socket socket = new Socket("localhost", 12345);
                - Usage: Read/write data through the connection

                Analogy:
                - ServerSocket = Phone waiting for calls
                - Socket = Making a call to that phone


                * FileSharer creates ServerSocket(12345) → Waits for connections
                * DownloadHandler creates Socket("localhost", 12345) → Initiates connection
                * ServerSocket.accept() accepts the connection → Returns a Socket representing the client
                * Data flows between the two sockets:
                * DownloadHandler reads from its socket
                * FileSharer writes to its client socket
                */
                try (Socket socket = new Socket("localhost", port);
                     InputStream socketInput = socket.getInputStream()) {
                    
                    File tempFile = File.createTempFile("download-", ".tmp");
                    String filename = "downloaded-file"; // Default filename
                    
                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        
                        ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
                        int b;
                        while ((b = socketInput.read()) != -1) {
                            if (b == '\n') break;
                            headerBaos.write(b);
                        }
                        /*
                        - Reads bytes one at a time from socket until newline (\n)
                        - Captures: "Filename: document.pdf"
                        */
                        
                        String header = headerBaos.toString().trim();
                        if (header.startsWith("Filename: ")) {
                            filename = header.substring("Filename: ".length());
                        }
                        
                        while ((bytesRead = socketInput.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                        /*
                        - Reads file bytes in 4KB chunks from socket
                        - Writes each chunk to temp file
                        - Continues until all file data is transferred
                        */
                    }
                    /*
                    This code reads the file from the P2P server and saves it to a temporary file, extracting both the filename and file content.
                    - Creates temporary file like /tmp/download-abc123.tmp
                    - 4KB buffer to read file chunks efficiently
                    - Extract filename
                    - Read and save file content
                    - tempFile now contains the complete file from the peer
                    - filename contains the original filename
                    - Ready to send to the browser as a download
                    */
                    
                    headers.add("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                    headers.add("Content-Type", "application/octet-stream");
                    
                    exchange.sendResponseHeaders(200, tempFile.length());
                    try (OutputStream os = exchange.getResponseBody();
                         FileInputStream fis = new FileInputStream(tempFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }

                        /*
                        - Reads temp file in 4KB chunks
                        - Streams each chunk to the browser
                        - Browser receives file and downloads it
                        */
                    }
                    /*
                    This sends the downloaded file back to the Next.js UI as an HTTP response, triggering a browser download.

                    headers.add("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                    - Tells the browser to download the file (not display it)
                    - Sets the download filename to the original name (e.g., "document.pdf")


                    headers.add("Content-Type", "application/octet-stream");
                    - Generic binary file type
                    - Works for any file type (PDF, image, video, etc.)

                    exchange.sendResponseHeaders(200, tempFile.length());
                    - 200 = Success
                    - tempFile.length() = File size in bytes (Content-Length)
                    - Sends headers to the browser


                    Browser receives:   
                    HTTP/1.1 200 OK
                    Content-Disposition: attachment; filename="document.pdf"
                    Content-Type: application/octet-stream
                    Content-Length: 12345

                    [binary file data...]
                    */
                    
                    tempFile.delete();
                    
                } catch (IOException e) {
                    System.err.println("Error downloading file from peer: " + e.getMessage());
                    String response = "Error downloading file: " + e.getMessage();
                    headers.add("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(500, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                }
                
            } catch (NumberFormatException e) {
                String response = "Bad Request: Invalid port number";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }
    }
}