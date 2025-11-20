package plethora.audio;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;

public class MP3Player {
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;

    /**
     * Constructor for loading MP3 from a file using a relative or absolute path.
     *
     * @param filePath Relative or absolute path to the MP3 file.
     */
    public MP3Player(String filePath) {
        loadFromFile(filePath);
    }

    /**
     * Constructor for loading MP3 from a URL.
     *
     * @param url URL of the MP3 file.
     */
    public MP3Player(URL url) {
        loadFromURL(url);
    }

    /**
     * Constructor for loading MP3 from an InputStream.
     *
     * @param inputStream InputStream containing the MP3 data.
     */
    public MP3Player(InputStream inputStream) {
        loadFromInputStream(inputStream);
    }

    public static void main(String[] args) {
        // Example usage with different constructors
        MP3Player player1 = new MP3Player("C:\\q.mp3");

        // Use player methods as needed...
        player1.play();
    }

    /**
     * Load an MP3 file from a relative or absolute path and initialize the MediaPlayer.
     *
     * @param filePath Relative or absolute path to the MP3 file.
     */
    private void loadFromFile(String filePath) {
        try {
            File file = new File(filePath);
            URI uri = file.toURI();
            Media media = new Media(uri.toString());
            initializeMediaPlayer(media);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Load an MP3 file from a URL and initialize the MediaPlayer.
     *
     * @param url URL of the MP3 file.
     */
    private void loadFromURL(URL url) {
        try {
            Media media = new Media(url.toExternalForm());
            initializeMediaPlayer(media);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Load an MP3 file from an InputStream and initialize the MediaPlayer.
     *
     * @param inputStream InputStream containing the MP3 data.
     */
    private void loadFromInputStream(InputStream inputStream) {
        try {
            File tempFile = File.createTempFile("temp", ".mp3");
            tempFile.deleteOnExit();
            Files.copy(inputStream, tempFile.toPath());

            URI uri = tempFile.toURI();
            Media media = new Media(uri.toString());
            initializeMediaPlayer(media);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Initialize the MediaPlayer with the provided Media object.
     *
     * @param media Media object representing the MP3 file.
     */
    private void initializeMediaPlayer(Media media) {
        mediaPlayer = new MediaPlayer(media);
        mediaPlayer.setOnEndOfMedia(() -> isPlaying = false);
    }

    /**
     * Play the loaded MP3 file.
     */
    public void play() {
        if (mediaPlayer != null) {
            mediaPlayer.play();
            isPlaying = true;
        }
    }

    /**
     * Pause the currently playing MP3 file.
     */
    public void pause() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
            isPlaying = false;
        }
    }

    /**
     * Check if the MP3 player is currently playing.
     *
     * @return true if playing, false otherwise.
     */
    public boolean isPlaying() {
        return isPlaying;
    }

    /**
     * Get the File object of the loaded MP3 file.
     *
     * @return The File object of the loaded MP3 file.
     */
    public File getAudioFile() {
        if (mediaPlayer != null) {
            String path = mediaPlayer.getMedia().getSource();
            try {
                return new File(new URI(path));
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
