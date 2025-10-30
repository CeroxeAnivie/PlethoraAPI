package plethora.about;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import plethora.javafx.FXMLReader;
import plethora.print.Printer;

import java.io.Serializable;

public class About extends Application implements Serializable {
    public static final String VERSION = "1.1-RELEASE";
    public static final String AUTHOR = "Ceroxe";
    private static final String LICENCE = "MIT License";

    public static void main(String[] args) {

        Printer.print("\n" + "   _____                                     \n" +
                "  / ____|                                    \n" +
                " | |        ___   _ __    ___   __  __   ___ \n" +
                " | |       / _ \\ | '__|  / _ \\  \\ \\/ /  / _ \\\n" +
                " | |____  |  __/ | |    | (_) |  >  <  |  __/\n" +
                "  \\_____|  \\___| |_|     \\___/  /_/\\_\\  \\___|\n" +
                "                                             \n" +
                "                                             ", Printer.color.PURPLE, Printer.style.ITALIC);

        System.out.println();
        Printer.print("Author: " + AUTHOR, Printer.color.GREEN, Printer.style.NONE);
        Printer.print("Version: " + VERSION, Printer.color.GREEN, Printer.style.NONE);
        Printer.print("Licence: " + LICENCE, Printer.color.GREEN, Printer.style.NONE);
        Application.launch(args);
    }

    @Override
    public void start(Stage stage) {
        try {
            FXMLReader fxmlReader = new FXMLReader(this.getClass().getClassLoader().getResource("about/fxml/AboutPanel.fxml"));
            AnchorPane anchorPane = (AnchorPane) fxmlReader.getPane();
            AboutPanelController aboutPanelController = (AboutPanelController) fxmlReader.getController();
            aboutPanelController.getAuthorLabel().setText("Author: " + AUTHOR);
            aboutPanelController.getVersionLabel().setText("Version: " + VERSION);
            aboutPanelController.getLicenceLabel().setText("Licence: " + LICENCE);
            Scene scene = new Scene(anchorPane);
            stage.setScene(scene);
            stage.setTitle("About");
            stage.setResizable(false);
            stage.show();


        } catch (Exception ignore) {
        }
    }
}
