package com.randude14.lotteryplus.tasks;

import com.randude14.lotteryplus.ChatUtils;
import com.randude14.lotteryplus.LotteryPlus;
import com.randude14.lotteryplus.configuration.Config;

public class ReminderMessageTask implements Task {
	private int updateId = -1;
	
	public void run() {
		ChatUtils.broadcastRaw("lottery.mess.reminder");
	}

	public void scheduleTask() {
		LotteryPlus.cancelTask(updateId);
		updateId = -1;
		if(!Config.getBoolean(Config.REMINDER_MESSAGE_ENABLE)) return;
		long delay = Config.getLong(Config.REMINDER_MESSAGE_TIME) * SERVER_SECOND * MINUTE;
		updateId = LotteryPlus.scheduleSyncRepeatingTask(this, delay, delay);
	}
}
