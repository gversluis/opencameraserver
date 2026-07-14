package net.sourceforge.opencamera.webserver;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.preview.Preview;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import fi.iki.elonen.NanoHTTPD;

public class Server extends NanoHTTPD {
    private static final String TAG = "Server";
    private static final String HOST = "localhost";
    private static final int PORT = 7967; // 79 67 = OC
    private static Server webserver = null;
    private final MainActivity mainActivity;

    public static void startServer(MainActivity mainActivity) {
        AtomicInteger counter = new AtomicInteger(0);
        final int retries = 100;
        if (webserver == null) webserver = new Server(HOST, PORT, mainActivity);
        try {
            if (!webserver.isAlive()) webserver.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (Exception e) {
            if (counter.incrementAndGet() > retries) {
                Log.e(TAG, "Webserver: Could not start after " + retries + " retries");
                return;
            }
            Log.e(TAG, "Webserver: " + e);
        }
        if (!webserver.isAlive())
            new Handler(Looper.getMainLooper()).postDelayed(() -> mainActivity.getPermissionHandler().requestInternetPermission(), 5000);
        Log.d(TAG, "Webserver: " + webserver.isAlive());
    }

    public static void stopServer() {
        if (webserver != null) webserver.stop();
    }

    public Server(String host, int port, MainActivity mainActivity) {
        super(host, port);
        this.mainActivity = mainActivity;
    }

    private static boolean waitForChange(
            Supplier<Boolean> successCheck,
            Supplier<Boolean> action,
            long timeoutMs
    ) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            while (!successCheck.get()) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Log.d(TAG, "waitForChange was interrupted: "+e);
                    return;
                }
            }
            latch.countDown();
        }).start();
        boolean result = action.get();
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && result;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();
            if (method == Method.GET && "/demo".equals(uri)) return demo();
            if (method == Method.GET && "/status".equals(uri)) return status();
            if (mainActivity.isCameraInBackground()) return error(Response.Status.CONFLICT, "Camera not in the foreground");
            if (method == Method.GET && "/capture".equals(uri)) return capture(session);
            if (method == Method.GET && "/camera".equals(uri)) return camera();
            if (method == Method.GET && "/latest".equals(uri)) return latest(session);
            if (method == Method.GET && "/preview".equals(uri)) return preview(session);
            if (method == Method.GET && "/burst".equals(uri)) return error(Response.Status.NOT_FOUND, "Burst not implemented yet");
            if (method == Method.GET && "/video".equals(uri)) return video();
            if (method == Method.GET && "/start".equals(uri)) return startRecording();
            if (method == Method.GET && "/stop".equals(uri)) return stopRecording();
            return error(Response.Status.NOT_FOUND,new Exception("Endpoint not found"));
        } catch (Exception e) {
            return error(Response.Status.INTERNAL_ERROR, e);
        }
    }

    private Response demo() {
        String html =
                "<!DOCTYPE html>"
                        + "<html>"
                        + "<head>"
                        + "<title>Demo</title>"
                        + "<style>"
                        + "html, body { min-height: 100%; }"
                        + "</style>"
                        + "</head>"
                        + "<body>"
                        + "<h1>Open Camera Server demo page</h1>"
                        + "<ul>"
                        + "<li><a href=\"/status\" target=\"demo\">Show status</a>"
                        + "<li><a href=\"/camera\" target=\"demo\">Switch camera</a>"
                        + "<li><a href=\"/preview?width=100&height=100&format=jpeg&quality=20\" target=\"demo\">Show jpeg preview</a>"
                        + "<li><a href=\"/preview?width=100&height=100&format=webp&quality=20\" target=\"demo\">Show webp preview</a>"
                        + "<li><a href=\"/preview?width=100&height=100&format=amazfit&quality=20\" target=\"demo\">Show amazfit tga preview</a>"
                        + "<li><a href=\"/latest?width=200&height=200&format=png&quality=90\" target=\"demo\">Latest png picture</a>"
                        + "<li><a href=\"/video\" target=\"demo\">Switch between picture and video</a>"
                        + "<li><a href=\"/capture\" target=\"demo\">Make photo</a>"
                        + "<li><a href=\"/capture?timerSeconds=2\" target=\"demo\">Make photo with 2s timer</a>"
                        + "<li><a href=\"/burst\" target=\"demo\">Burst</a>"
                        + "<li><a href=\"/start\" target=\"demo\">Start recording</a>"
                        + "<li><a href=\"/stop\" target=\"demo\">Stop recording</a>"
                        + "</ul>"
                        + "<iframe name=\"demo\" style=\"width:100%; min-height:50vh\"></iframe>"
                        + "</body>"
                        + "</html>";
        return ok(html);
    }

    private Response status() {
        boolean isVideo = mainActivity.getPreview() != null && mainActivity.getPreview().isVideo();
        String json =
                "{"
                        + "\"active\":" + (!mainActivity.isCameraInBackground() && !mainActivity.isAppPaused()) + ","
                        + "\"appInBackground\":" + mainActivity.isCameraInBackground() + ","
                        + "\"appIsPaused\":" + mainActivity.isAppPaused() + ","
                        + "\"video\":" + isVideo + ","
                        + "\"photo\":" + !isVideo + ","
                        + "\"recording\":" + mainActivity.getPreview().isVideoRecording() + ","
                        + "\"photoMode\": \"" + mainActivity.getApplicationInterface().getPhotoMode() + "\","
                        + "\"isMultiCamEnabled\":" + (mainActivity.isMultiCam() && mainActivity.isMultiCamEnabled())
                        + "}";
        return ok(json);
    }

    private Response capture(IHTTPSession session) throws InterruptedException {
        if (enableVideo(false)) {
            List<String> timerList = session.getParameters().get("timerSeconds");
            int timerDurationSeconds = 0;
            if (timerList != null && !timerList.isEmpty()) {
                try {
                    timerDurationSeconds = Integer.parseInt(timerList.get(0));
                } catch (NumberFormatException ignored) {
                }
            }
            Preview preview = mainActivity.getPreview();
            if (preview != null && preview.isOnTimer()) preview.cancelTimer();

            if (timerDurationSeconds > 0) {
                @SuppressWarnings("deprecation") SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity.getApplicationContext());
                String key = PreferenceKeys.TimerPreferenceKey;
                String oldTimer = sharedPreferences.getString(key, "0");
                sharedPreferences.edit().putString(key, String.valueOf(timerDurationSeconds)).apply();
                Bitmap oldBitmap = mainActivity.gallery_bitmap;
                if (waitForChange(
                        () -> oldBitmap != mainActivity.gallery_bitmap,
                        () -> {
                            try {
                                View view = preview.getView();
                                view.post(() -> mainActivity.clickedTakePhoto(view));
                            } catch(Exception ignore) {}
                            return true;
                        },
                        3000
                )) {
                    sharedPreferences.edit().putString(key, oldTimer).apply();
                    return ok("{\"success\":true,\"message\":\"Picture taken\"}");
                }
                sharedPreferences.edit().putString(key, oldTimer).apply();
                return error(Response.Status.INTERNAL_ERROR,new Exception("Failed to take photo"));
            } else {
                Bitmap oldBitmap = mainActivity.gallery_bitmap;
                if (waitForChange(
                        () -> oldBitmap != mainActivity.gallery_bitmap,
                        () -> {
                            try {
                                View view = preview.getView();
                                view.post(() -> mainActivity.clickedTakePhoto(view));
                            } catch(Exception ignore) {}
                            return true;
                        },
                        3000
                )) {
                    return ok("{\"success\":true,\"message\":\"Picture taken\"}");
                }
                return error(Response.Status.INTERNAL_ERROR,new Exception("Failed to take photo"));
            }
        }
        return error(Response.Status.NOT_FOUND,new Exception("Could not switch to photo mode"));
    }

    private Response camera() throws InterruptedException {
        Preview preview = mainActivity.getPreview();
        if (preview != null) {
            if( preview.canSwitchCamera() ) {
                int oldNextCameraId = mainActivity.getNextCameraId();
                if (waitForChange(
                        () -> mainActivity.getNextCameraId() != oldNextCameraId,
                        () -> {
                            try {
                                View view = preview.getView();
                                view.post(() -> mainActivity.clickedSwitchCamera(view));
                            } catch(Exception ignore) {}
                            return true;
                        },
                        3000
                )) {
                    return ok("{\"success\":true,\"message\":\"Camera switched to "+preview.getCameraId()+"\"}");
                }
                return error(Response.Status.NOT_ACCEPTABLE, "Failed to switch camera");
            }
            return error(Response.Status.NOT_ACCEPTABLE, "Multi camera not supported");
        } else {
            return error(Response.Status.NOT_ACCEPTABLE, "Can not switch because camera is not active");
        }
    }

    private Response latest(IHTTPSession session) throws IOException {
        Log.d(TAG, "Getting latest image from gallery");
        return image(session, mainActivity.gallery_bitmap, "latest");
    }

    private Response preview(IHTTPSession session) throws IOException {
        Preview preview = mainActivity.getPreview();
        boolean oldPreviewBitmapSmall = preview.usePreviewBitmapSmall();
        boolean oldPreviewBitmapFull = preview.usePreviewBitmapFull();
        preview.enablePreviewBitmap(true, true);
        TextureView textureView = (TextureView)preview.getView();
        Bitmap previewBitmap = textureView.getBitmap();
        if (previewBitmap==null) throw new IOException("Preview not available");

        int rotation = preview.getDisplayRotationDegrees(false);
        Matrix matrix = new Matrix();
        matrix.postRotate(-rotation);
        previewBitmap = Bitmap.createBitmap(previewBitmap, 0, 0, previewBitmap.getWidth(), previewBitmap.getHeight(), matrix, false);
        boolean sideways = rotation == 90 || rotation == 270;
        if (sideways) previewBitmap = Bitmap.createScaledBitmap(previewBitmap, textureView.getWidth(), textureView.getHeight(), false);

        Log.d(TAG, "Got preview rotation "+rotation);
        if (previewBitmap==null) {
            return error(Response.Status.NO_CONTENT, "Preview not available");
        }
        Response response = image(session, previewBitmap, "preview");
        preview.enablePreviewBitmap(oldPreviewBitmapSmall, oldPreviewBitmapFull);
        return response;
    }

    private Response video() throws InterruptedException {
        boolean from = mainActivity.getPreview().isVideo();
        if (enableVideo(!from)) {
            return ok("{\"success\":true,\"message\":\"Switched from "+(from?"video":"photo")+" to "+(mainActivity.getPreview().isVideo()?"video":"photo")+"\"}");
        }
        return error(Response.Status.NOT_FOUND,new Exception("Could not switch between photo/video"));
    }

    private Response startRecording() throws InterruptedException {
        Preview preview = mainActivity.getPreview();
        if (preview != null) {
            if (!preview.isVideoRecording()) {
                if (enableVideo(true)) {
                    if (waitForChange(
                            () -> preview.isVideoRecording() == true,
                            () -> {
                                try {
                                    View view = preview.getView();
                                    view.post(() -> mainActivity.clickedTakePhoto(view));
                                } catch (Exception ignore) { }
                                return true;
                            },
                            3000
                    )) {
                        return ok("{\"success\":true,\"message\":\"Started recording\"}");
                    }
                    return error(Response.Status.NOT_FOUND, new Exception("Could not start recording"));
                }
                return error(Response.Status.NOT_FOUND, new Exception("Could not switch to video"));
            }
            return ok("{\"success\":true,\"message\":\"Already recording\"}");
        }
        return error(Response.Status.NOT_ACCEPTABLE, "Can not switch because camera is not active");
    }

    private Response stopRecording() throws InterruptedException {
        Preview preview = mainActivity.getPreview();
        if (preview != null) {
            if (preview.isVideoRecording()) {
                if (enableVideo(true)) {
                    if (waitForChange(
                            () -> preview.isVideoRecording() == true,
                            () -> {
                                try {
                                    View view = preview.getView();
                                    view.post(() -> mainActivity.clickedTakePhoto(view));
                                } catch (Exception ignore) {}
                                return true;
                            },
                            3000
                    )) {
                        return ok("{\"success\":true,\"message\":\"Stopped recording\"}");
                    }
                    return error(Response.Status.NOT_FOUND, new Exception("Could not stop recording"));
                }
                return error(Response.Status.NOT_FOUND, new Exception("Could not switch to video"));
            }
            return ok("{\"success\":true,\"message\":\"Already stopped recording\"}");
        }
        return error(Response.Status.NOT_ACCEPTABLE, "Can not switch because camera is not active");
    }

    private boolean enableVideo(boolean active) throws InterruptedException {
        Preview preview = mainActivity.getPreview();
        if (preview == null) throw new InterruptedException("Missing preview to switch to "+(active?"video":"photo"));
        if (active == preview.isVideo()) return true;
        return waitForChange(
                () -> preview.isVideo() == active && preview.isPreviewStarted(),
                () -> {
                    try {
                        View view = preview.getView();
                        view.post(() -> mainActivity.clickedSwitchVideo(view));
                    } catch(Exception ignore) {}
                    return true;
                },
                3000
        );
    }

    private Response ok(String content) {
        String mimeType;
        Log.d(TAG, "Response OK "+content.stripLeading().charAt(0)+" "+content);
        mimeType = switch (content.stripLeading().charAt(0)) {
            case '<' -> "text/html";
            case '{' -> "application/json";
            default -> "text/plain";
        };
        return newFixedLengthResponse(Response.Status.OK, mimeType, content);
    }

    private Response error(Response.Status status, Exception e) {
        String sStackTrace = "Stacktrace: ";
        if (e != null) {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                sStackTrace = "Stacktrace " + e.getClass() + ": " + sw;
                pw.close();
                sw.close();
            } catch(IOException e2) {
                sStackTrace = "Could not get stacktrace: "+e2.getMessage();
            }
        }
        return error(status, e.getMessage(), sStackTrace);
    }

    private Response error(Response.Status status, String message) {
        return error(status, message, null);
    }

    private Response error(Response.Status status, String message, String stackTrace) {
        message = message == null ? null : message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "    ").replace("\n", "\\n");
        stackTrace = stackTrace == null ? "" : String.join("\",\"", stackTrace.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "    ").split("\n"));
        return newFixedLengthResponse(
                status,
                "application/json",
                "{"
                        + "\"success\":false"
                        + ", \"message\":\"" + message + "\""
                        + (stackTrace.isEmpty() ? "" : ", \"stacktrace\": [\"" + stackTrace + "\"]")
                        + "}"
        );
    }


    private Response image(IHTTPSession session, Bitmap bitmap, String filename) throws IOException {
        Bitmap scaled = bitmap;
        String format = "";
        String mimeType;
        int quality = 90;
        int height = 0;
        int width = 0;
        boolean compress = true;

        if (bitmap==null) throw new IOException("Image not available");

        try {
            format = session.getParameters().get("format").get(0).toLowerCase();
        } catch (Exception ignore) {
        }
        try {
            quality = Integer.parseInt(session.getParameters().get("quality").get(0));
            quality = Math.max(0, Math.min(100, quality));
        } catch (Exception ignored) {
        }
        try {
            height = Integer.parseInt(session.getParameters().get("height").get(0));
        } catch (Exception ignored) {
        }
        try {
            width = Integer.parseInt(session.getParameters().get("width").get(0));
        } catch (Exception ignored) {
        }
        try {
            String compressParameter = session.getParameters().get("compress").get(0).toLowerCase();
            if (compressParameter.equals("false") || compressParameter.equals("no") || compressParameter.equals("0")) compress = false;
        } catch (Exception ignored) {
        }
        if (width > 0 && height > 0) {
            if (bitmap.getWidth() > bitmap.getHeight()) height = 0; else width = 0;
        }
        if (width == 0 && height > 0) width = height * bitmap.getWidth() / bitmap.getHeight();
        if (height == 0 && width > 0) height = width * bitmap.getHeight() / bitmap.getWidth();
        /*
        if (format.equals("amazfit")) {
            int newWidth = width - width % 16;    // scale down to width dividable by 16
            height = newWidth * 1000 / width * height / 1000; // scale down accordingly, prevent floating point rounding errors
            width = newWidth;
        }
        */
        if (width==0 && height > 0) width = format.equals("amazfit") ? 16 : 1;
        if (height==0 && width > 0) height = 1;
        if (width > 0 && height > 0) {
            Log.d(TAG, "Scaling to: "+width+"x"+height);
            scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Bitmap.CompressFormat compressFormat = null;
        switch (format) {
            case "amazfit":
                mimeType = "image/x-tga";
                filename += ".tga";
                // int colors = Math.toIntExact(Math.round(2.0 * Math.pow(maxColors / 2.0, quality / 100.0)));
                try {
                    baos = AmazfitFormat.getBytes(scaled, quality, compress);
                } catch (IOException e) {
                    throw new IOException("Could not write Amazfit format: "+e);
                }
                break;
            case "webp":
                mimeType = "image/webp";
                filename += ".webp";
                compressFormat = Bitmap.CompressFormat.WEBP;
                break;
            case "png":
                mimeType = "image/png";
                filename += ".png";
                compressFormat = Bitmap.CompressFormat.PNG;
                break;
            case "jpg":
            case "jpeg":
            default:
                mimeType = "image/jpeg";
                filename += ".jpg";
                compressFormat = Bitmap.CompressFormat.JPEG;
        }
        if (compressFormat!=null) {
            scaled.compress(
                compressFormat,
                quality,
                baos
            );
        }
        byte[] jpegData = baos.toByteArray();
        Response response = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                new ByteArrayInputStream(jpegData),
                jpegData.length
        );
        response.addHeader("Content-Disposition", "inline; filename=\""+filename+"\"");
        return response;
    }
}