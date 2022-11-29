package com.supalosa;

import com.supalosa.bot.SupaBot;
import com.supalosa.bot.debug.JFrameDebugTarget;
import com.supalosa.bot.debug.NoOpDebugTarget;

public class LadderMain {

    public static void main(String[] args) {
        // Change this back to NoOp for ladder.
        //SupaBot supaBot = new SupaBot(false, new JFrameDebugTarget());
        SupaBot supaBot = new SupaBot(false, new NoOpDebugTarget());
        LauncherUtils.startLadder(args, supaBot);
    }
}