package com.supalosa;

import com.github.ocraft.s2client.api.controller.ExecutableParser;
import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.bot.setting.PlayerSettings;
import com.github.ocraft.s2client.protocol.game.AiBuild;
import com.github.ocraft.s2client.protocol.game.Difficulty;
import com.github.ocraft.s2client.protocol.game.LocalMap;
import com.github.ocraft.s2client.protocol.game.Race;
import com.supalosa.bot.SupaBot;
import com.supalosa.bot.debug.JFrameDebugTarget;
import com.supalosa.bot.debug.NoOpDebugTarget;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.ocraft.s2client.api.OcraftApiConfig.*;

public class LocalMain2 {

    public static void main(String[] args) throws IOException, InterruptedException {

        Path gameRoot = Paths.get("D:\\StarCraft II\\Versions\\Base75689\\SC2_x64.exe");
        List<String> processArgs = List.of(
                gameRoot.toString(),
                "-listen", "127.0.0.1",
                "-port", "15001",
                "-displayMode", "0",
                "-dataVersion", "B89B5D6FA7CBF6452E721311BFBC6CB2",
                "-windowwidth", "500",
                "-windowheight", "300",
                "-windowx", "0",
                "-windowy", "0",
                "-verbose");
        Process sc2Process = new ProcessBuilder(processArgs)
                .redirectErrorStream(true)
                .directory(new File("D:\\Starcraft II\\Support64"))
                .start();
        Thread.sleep(10000);
        SupaBot supaBot = new SupaBot(true, new JFrameDebugTarget());
        S2Coordinator s2Coordinator = S2Coordinator.setup()
                .setTimeoutMS(300000)
                .loadSettings(args)
                .setPortStart(5001)
                //.setProcessPath(Path.of("D:\\StarCraft II\\Versions\\Base75689\\SC2_x64.exe"))
                //.setDataVersion("B89B5D6FA7CBF6452E721311BFBC6CB2")
                .setParticipants(
                        S2Coordinator.createParticipant(Race.TERRAN, supaBot, "Ocraft_Bot02"))
                //.launchStarcraft();
                .connect("127.0.0.1", 15001);

        s2Coordinator.setupPorts(2, () -> 8642, false);
        s2Coordinator.joinGame();

        while (s2Coordinator.update()) {
        }
        s2Coordinator.quit();
        //sc2Process.destroy();
    }

    private static void blah(String[] args) throws InterruptedException {
        //Path gameRoot = Paths.get("D:\\StarCraft II\\Versions\\Base75689\\SC2_x64.exe");
        /*Path gameRoot = Paths.get("D:\\StarCraft II\\Versions\\Base88500\\SC2_x64.exe");
        List<String> processArgs = List.of(
                gameRoot.toString(),
                "-listen", "127.0.0.1",
                "-port", "5000",
                "-displayMode", "0",
                "-windowwidth", "500",
                "-windowheight", "300",
                "-windowx", "0",
                "-windowy", "0");
        Process sc2Process = new ProcessBuilder(processArgs)
                .redirectErrorStream(true)
                .directory(new File("D:\\Starcraft II\\Support64"))
                .start();*/

        //Thread.sleep(3000);

        PlayerSettings opponent = S2Coordinator.createComputer(Race.ZERG, Difficulty.VERY_HARD, AiBuild.RANDOM_BUILD);
        PlayerSettings[] participants = new PlayerSettings[1];
        SupaBot bot = new SupaBot(true, new JFrameDebugTarget());
        participants[0] = S2Coordinator.createParticipant(Race.TERRAN, bot, "supabot");
        //participants[1] = opponent;
        S2Coordinator vS2Coordinator = S2Coordinator.setup()
                .setTimeoutMS(300000)
                .setRawAffectsSelection(true)
                .setShowCloaked(true)
                .setShowBurrowed(true)
                .setRealtime(false)
                .setParticipants(participants)
                .connect("127.0.0.1", 8765);

        vS2Coordinator.setupPorts(2, () -> 8765, false);
        //vS2Coordinator = vS2Coordinator.startGame(LocalMap.of(Paths.get("2000AtmospheresAIE.SC2Map")));
        //System.out.println("Game created, joining...");
        //Thread.sleep(60000);
        vS2Coordinator.joinGame();
        System.out.println("joined, waiting for update");

        Thread.sleep(10000);
        while (vS2Coordinator.update()) {
        }
        vS2Coordinator.quit();
        //sc2Process.destroy();
    }
}