package com.supalosa;

import com.github.ocraft.s2client.bot.S2Coordinator;
import com.github.ocraft.s2client.bot.setting.PlayerSettings;
import com.github.ocraft.s2client.protocol.game.BattlenetMap;
import com.github.ocraft.s2client.protocol.game.Difficulty;
import com.github.ocraft.s2client.protocol.game.Race;
import com.supalosa.bot.SupaBot;
import com.supalosa.bot.debug.NoOpDebugTarget;

public class LadderMain {

    public static void main(String[] args) {
        SupaBot supaBot = new SupaBot(false, new NoOpDebugTarget());
        LauncherUtils.startLadder(args, supaBot);
    }
}