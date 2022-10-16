package com.supalosa;

import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.bot.setting.PlayerSettings;
import com.github.ocraft.s2client.protocol.game.BattlenetMap;
import com.github.ocraft.s2client.protocol.game.LocalMap;
import com.github.ocraft.s2client.protocol.game.Race;
import com.supalosa.bot.SupaBot;

import java.nio.file.Paths;
import java.util.Arrays;

public class LauncherUtils {

    public static void startLadder(String[] pArgs, SupaBot pBot) {
        S2Coordinator vS2Coordinator = S2Coordinator.setup()
                .setTimeoutMS(300000)
                .setRawAffectsSelection(true)
                .setShowCloaked(true)
                .setShowBurrowed(true)
                .setRealtime(false)
                .loadLadderSettings(pArgs)
                .setParticipants(S2Coordinator.createParticipant(Race.TERRAN, pBot))
                .connectToLadder()
                .joinGame();
        executeCoordinator(vS2Coordinator);
    }

    public static void startSC2(String[] pArgs, SupaBot pBot, BattlenetMap pMap, boolean pRealtime, PlayerSettings[] pAI) {
        PlayerSettings[] participants = new PlayerSettings[2];
        participants[0] = S2Coordinator.createParticipant(Race.TERRAN, pBot, "supabot");
        participants[1] = pAI[0];
        S2Coordinator vS2Coordinator = S2Coordinator.setup().setRealtime(pRealtime).setRawAffectsSelection(false)
                .loadSettings(pArgs)
                .setShowCloaked(true)
                .setShowBurrowed(true)
                .setStepSize(2)
                .setParticipants(participants)
                .launchStarcraft()
                .startGame(pMap);
        executeCoordinator(vS2Coordinator);
    }

    public static void startSC2(String[] pArgs, SupaBot pBot, LocalMap pMap, boolean pRealtime, PlayerSettings[] pAI) {
        PlayerSettings[] participants = new PlayerSettings[2];
        participants[0] = S2Coordinator.createParticipant(Race.TERRAN, pBot, "supabot");
        participants[1] = pAI[0];
        S2Coordinator vS2Coordinator = S2Coordinator.setup().setRealtime(pRealtime).setRawAffectsSelection(false)
                .loadSettings(pArgs)
                .setShowCloaked(true)
                .setShowBurrowed(true)
                .setStepSize(2)
                .setParticipants(participants)
                .launchStarcraft()
                .startGame(pMap);
        executeCoordinator(vS2Coordinator);
    }

    private static void executeCoordinator(S2Coordinator pS2Coordinator) {
        while (pS2Coordinator.update()) {
        }
        pS2Coordinator.quit();
    }
}
