package com.supalosa;

import com.github.ocraft.s2client.api.controller.ExecutableParser;
import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.bot.setting.PlayerSettings;
import com.github.ocraft.s2client.protocol.game.*;
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

public class LocalMain {

    public static void main(String[] args) throws IOException, InterruptedException {

        // see https://github.com/ocraft/ocraft-s2client/blob/master/ocraft-s2client-protocol/src/main/resources/versions.json
        Path gameRoot = Paths.get("D:\\StarCraft II\\Versions\\Base75689\\SC2_x64.exe");
        List<String> processArgs = List.of(
                gameRoot.toString(),
                "-listen", "0.0.0.0",
                "-port", "15000",
                "-displayMode", "0",
                "-dataVersion", "B89B5D6FA7CBF6452E721311BFBC6CB2",
                "-windowwidth", "500",
                "-windowheight", "300",
                "-windowx", "0",
                "-windowy", "0",
                "-verbose");
        ProcessBuilder builder = new ProcessBuilder(processArgs)
                .redirectErrorStream(true)
                .inheritIO()
                .directory(new File("D:\\Starcraft II\\Support64"));
        Process sc2Process = null;
        try {
            //sc2Process = builder.start();
            //Thread.sleep(10000);
            SupaBot supaBot = new SupaBot(true, new JFrameDebugTarget());
            MultiplayerOptions multiplayerOptions = MultiplayerOptions.multiplayerSetup()
                    .sharedPort(8002)
                    .serverPort(PortSet.of(8003, 8004))
                    .clientPorts(
                            PortSet.of(8005, 8006))
                    .build();
            S2Coordinator s2Coordinator = S2Coordinator.setup()
                    .setTimeoutMS(300000)
                    .loadSettings(args)
                    //.setProcessPath(Path.of("D:\\StarCraft II\\Versions\\Base75689\\SC2_x64.exe"))
                    //.setDataVersion("B89B5D6FA7CBF6452E721311BFBC6CB2")
                    .setMultiplayerOptions(multiplayerOptions)
                    //.setMultiplayerOptions(MultiplayerOptions.multiplayerSetupFor(8001, 3))
                    .setParticipants(
                            S2Coordinator.createParticipant(Race.TERRAN, supaBot, "Ocraft_Bot01"),
                            S2Coordinator.createParticipant(Race.TERRAN))
                    //.connect("127.0.0.1", 8000)
                    .launchStarcraft()
                    //.joinGame();
                    .startGame(LocalMap.of(Paths.get("2000AtmospheresAIE.SC2Map")));

            while (s2Coordinator.update()) {
            }
            s2Coordinator.quit();
        } finally {
            if (sc2Process != null) {
                sc2Process.destroy();
            }
        }

    }
}