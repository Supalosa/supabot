package com.supalosa;

import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.protocol.game.LocalMap;
import com.github.ocraft.s2client.protocol.game.MultiplayerOptions;
import com.github.ocraft.s2client.protocol.game.PortSet;
import com.github.ocraft.s2client.protocol.game.Race;
import com.supalosa.bot.SupaBot;
import com.supalosa.bot.debug.JFrameDebugTarget;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

// Launch SC2 client for a docker/other agent to connect to.
public class LaunchClientSc2 {

    public static void main(String[] args) throws IOException, InterruptedException {

        // see https://github.com/ocraft/ocraft-s2client/blob/master/ocraft-s2client-protocol/src/main/resources/versions.json
        /*Path gameRoot = Paths.get("D:\\StarCraft II\\Versions\\Base75689\\SC2_x64.exe");
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
                "-verbose");*/
        //Path gameRoot = Paths.get("D:\\StarCraft II\\Versions\\Base88500\\SC2_x64.exe");
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
        int result = new ProcessBuilder(processArgs)
            .redirectErrorStream(true)
            .inheritIO()
            .directory(new File("D:\\Starcraft II\\Support64"))
            .start()
            .waitFor();
        System.exit(result);
    }
}