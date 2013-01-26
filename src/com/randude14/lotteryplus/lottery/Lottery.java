package com.randude14.lotteryplus.lottery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.randude14.lotteryplus.ChatUtils;
import com.randude14.lotteryplus.ClaimManager;
import com.randude14.lotteryplus.Logger;
import com.randude14.lotteryplus.LotteryManager;
import com.randude14.lotteryplus.Plugin;
import com.randude14.lotteryplus.Utils;
import com.randude14.lotteryplus.WinnersManager;
import com.randude14.lotteryplus.configuration.Config;
import com.randude14.lotteryplus.register.economy.Economy;
import com.randude14.lotteryplus.register.economy.MaterialEconomy;
import com.randude14.lotteryplus.register.economy.VaultEconomy;
import com.randude14.lotteryplus.util.FormatOptions;
import com.randude14.lotteryplus.util.TimeConstants;

public class Lottery implements FormatOptions, TimeConstants, Runnable {
	private final LotteryTimer timer;
	private final Map<String, Long> cooldowns;
	private final List<Reward> rewards;
	private final List<Sign> signs;
	private final String lotteryName;
	private final Random rand;
	private LotteryOptions options;
	private Economy econ;
	private boolean success;
	private int drawId;

	public Lottery(String name) {
		this.cooldowns = Collections.synchronizedMap(new HashMap<String, Long>());
		this.rewards = new ArrayList<Reward>();
		this.signs = new ArrayList<Sign>();
		this.lotteryName = name;
		this.timer = new LotteryTimer(this);
		this.rand = new Random();
		success = false;
	}

	public LotteryOptions getOptions() {
		return options;
	}

	public final String getName() {
		return lotteryName;
	}

	public boolean isDrawing() {
		return options.getBoolean("drawing", false);
	}

	public boolean isRunning() {
		return timer.isRunning();
	}

	public boolean isItemOnly() {
		int num = 0;
		for (Reward reward : rewards) {
			if (reward instanceof ItemReward)
				num++;
		}
		return options.getBoolean(Config.DEFAULT_ITEM_ONLY) && num > 0;
	}
	
	public Economy getEconomy() {
		return econ;
	}

	//called every second
	public void onTick() {
		timer.onTick();
		if (timer.isOver()) {
			this.draw();
			return;
		}
		printWarningTimes();
		updateSigns();
		updateCooldowns();
	}
	
	private void printWarningTimes() {
		String line = options.getString(Config.DEFAULT_WARNING_TIMES);
		if(line != null && !line.isEmpty()) {
			for(String timeStr : line.split("\\s+")) {
				int len = timeStr.length();
				if(len == 0) {
					continue;
				}
				try {
					long time = Long.parseLong(timeStr.substring(0, len-1));
					String timeMess = time + " ";
					char c = Character.toLowerCase(timeStr.charAt(len-1));
					switch(c) {
					case 'w':
						time = WEEK * time;
						timeMess += "week(s)";
					    break;
					case 'd':
						time = DAY * time;
						timeMess += "day(s)";
					    break;
					case 'h':
						time = HOUR * time;
						timeMess += "hour(s)";
					    break;
					case 'm':
						time = MINUTE * time;
						timeMess += "minute(s)";
					    break;
					default:
						//no need to do anything with time, already in seconds
						timeMess += "second(s)";
						break;
					}
					if(timer.getTime() == time) {
						ChatUtils.broadcastRaw("lottery.mess.warning", "<name>", lotteryName, "<time>", timeMess);
					}
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}
	}
	
	private void updateSigns() {
		updateSigns(false);
	}
	
	private void updateSigns(boolean over) {
		final String line1 = ChatUtils.replaceColorCodes(Config.getString(Config.SIGN_TAG));
		final String line2, line3, line4;
		if(over) {
			line2 = ChatUtils.replaceColorCodes(format(Config.getString(Config.OVER_SIGN_LINE_TWO)));
			line3 = ChatUtils.replaceColorCodes(format(Config.getString(Config.OVER_SIGN_LINE_THREE)));
			line4 = ChatUtils.replaceColorCodes(format(Config.getString(Config.OVER_SIGN_LINE_FOUR)));
		} else {
			if(isDrawing()) {
				line2 = ChatUtils.replaceColorCodes(format(Config.getString(Config.DRAWING_SIGN_LINE_TWO)));
				line3 = ChatUtils.replaceColorCodes(format(Config.getString(Config.DRAWING_SIGN_LINE_THREE)));
				line4 = ChatUtils.replaceColorCodes(format(Config.getString(Config.DRAWING_SIGN_LINE_FOUR)));
			} else {
				line2 = ChatUtils.replaceColorCodes(format(Config.getString(Config.UPDATE_SIGN_LINE_TWO)));
				line3 = ChatUtils.replaceColorCodes(format(Config.getString(Config.UPDATE_SIGN_LINE_THREE)));
				line4 = ChatUtils.replaceColorCodes(format(Config.getString(Config.UPDATE_SIGN_LINE_FOUR)));
			}
		}
		Plugin.scheduleSyncDelayedTask(new Runnable() {
			public void run() {
				for(Sign sign : signs) {
					if(!sign.getChunk().isLoaded()) 
						continue;
					sign.setLine(0, line1);
					sign.setLine(1, line2);
					sign.setLine(2, line3);
					sign.setLine(3, line4);
					sign.update(true);
				}
			}
		}, 0);
	}
	
	private void updateCooldowns() {
		synchronized(cooldowns) {
			Iterator<Map.Entry<String, Long>> it = cooldowns.entrySet().iterator();
			while(it.hasNext()) {
				Map.Entry<String, Long> entry = it.next();
				long cooldown = entry.getValue();
				if(cooldown-- <= 0)
					it.remove();
				else
					entry.setValue(cooldown);
			}
		}
	}

	public String format(String mess) {
		String winner = options.getString("winner", "");
		return mess
				.replace(FORMAT_REWARD, formatReward())
				.replace(FORMAT_TIME, timer.format())
				.replace(FORMAT_NAME, lotteryName)
				.replace(FORMAT_WINNER,
						(!winner.isEmpty()) ? winner : "no winner yet")
				.replace(
						FORMAT_TICKET_COST,
						econ.format(options
								.getDouble(Config.DEFAULT_TICKET_COST)))
				.replace(
						FORMAT_TICKET_TAX,
						String.format("%,.2f",
								options.getDouble(Config.DEFAULT_TICKET_TAX)))
				.replace(
						FORMAT_POT_TAX,
						String.format("%,.2f",
								options.getDouble(Config.DEFAULT_POT_TAX)));
	}

	private String formatReward() {
		if (!isItemOnly())
			return econ.format(options.getDouble(Config.DEFAULT_POT));
		int num = 0;
		for (int cntr = 0; cntr < rewards.size(); cntr++) {
			if (rewards instanceof ItemReward)
				num++;
		}
		return num + " item(s)";
	}

	public synchronized boolean addToPot(CommandSender sender, double add) {
		if (isItemOnly()) {
			ChatUtils.send(sender, "lottery.error.nopot", "<lottery>", lotteryName);
			return false;
		}
		if(sender instanceof Player) {
			Player player = (Player) sender;
			if(!econ.hasAccount(player)) {
				ChatUtils.send(player, "lottery.error.noaccount");
				return false;
			}
			if(!econ.hasEnough(player, add)) {
				ChatUtils.send(player, "lottery.error.notenough", "<money>", econ.format(add));
				return false;
			}
			econ.withdraw(player, add);
		}
		double pot = options.getDouble(Config.DEFAULT_POT);
		options.set(Config.DEFAULT_POT, pot + add);
		ChatUtils.send(sender, "plugin.command.atp.mess", "<money>", econ.format(add), "<lottery>", lotteryName);
		return true;
	}

	public void setOptions(LotteryOptions options) throws InvalidLotteryException {
		setOptions(options, false);
	}

	public void setOptions(LotteryOptions options, boolean force) throws InvalidLotteryException {
		try {
			// CHECK FOR NEGATIVE OPTIONS
			double time = options.getDouble(Config.DEFAULT_TIME);
			double pot = options.getDouble(Config.DEFAULT_POT);
			double ticketCost = options.getDouble(Config.DEFAULT_TICKET_COST);
			Validate.isTrue(time >= 0, ChatUtils.getNameFor("lottery.error.negative.time", "<time>", time));
			Validate.isTrue(pot >= 0.0, ChatUtils.getNameFor("lottery.error.negative.pot", "<pot>", pot));
			Validate.isTrue(ticketCost >= 0.0, ChatUtils.getNameFor("lottery.error.negative.ticket-cost", "<ticket_cost>", ticketCost));

			if (force) {
				rewards.clear();
			} else {
				transfer(this.options, options);
				signs.clear();
				int cntr = 1;
				while(options.contains("sign" + cntr)) {
					String str = options.getString("sign" + cntr);
					Location loc = Utils.parseToLocation(str);
					if(loc != null) {
						Block block = loc.getBlock();
						if(Plugin.isSign(block)) {
							Sign sign = (Sign) block.getState();
							signs.add(sign);
						} else {
							Logger.info("lottery.error.sign.load", "<loc>", str);
							options.remove("sign" + cntr);
						}
					} else {
						Logger.info("lottery.error.loc.load", "<line>", str);
						options.remove("sign" + cntr);
					}
					cntr++;
				}
			}

			this.options = options;
			
			//ECONOMY
			econ = null;
			if(options.getBoolean(Config.DEFAULT_USE_VAULT)) {
				if(VaultEconomy.isVaultInstalled()) {
					econ = new VaultEconomy();
				}
			} else {
				int materialID = Config.getInt(Config.DEFAULT_MATERIAL_ID);
				String name = Config.getString(Config.DEFAULT_MATERIAL_NAME);
				econ = new MaterialEconomy(materialID, name);
			}
			
			if(econ == null) {
				throw new NullPointerException("Failed to load an economy.");
			}

			// SET SEED FOR RANDOM
			rand.setSeed(Utils.loadSeed(options.getString(Config.DEFAULT_SEED)));

			// LOAD ITEM REWARDS
			String itemRewards = options.getString(Config.DEFAULT_ITEM_REWARDS);
			if (!(itemRewards == null || itemRewards.equals(""))) {
				for(ItemStack item : Utils.getItemStacks(itemRewards)) {
					rewards.add(new ItemReward(item));
				}
			}

			// LOAD TIME
			timer.setRunning(true);
			if (options.contains("save-time") && options.contains("reset-time")) {
				long saveTime = options.getLong("save-time", 0L);
				timer.setTime(saveTime);
				long resetTime = options.getLong("reset-time", 0L);
				timer.setResetTime(resetTime);
			} else {
				long t = (long) Math.floor(time * (double)HOUR);
				timer.setTime(t);
				timer.setResetTime(t);
			}
		} catch (Exception ex) {
			throw new InvalidLotteryException("Failed to load options.", ex);
		}
	}

	private void transfer(LotteryOptions oldOptions, LotteryOptions newOptions) {
		if (oldOptions == null || success) {
			rewards.clear();
			return;
		}
		if (!oldOptions.getBoolean(Config.DEFAULT_CLEAR_POT)) {
			double pot = newOptions.getDouble(Config.DEFAULT_POT);
			newOptions.set(Config.DEFAULT_POT,
					pot + oldOptions.getDouble(Config.DEFAULT_POT));
		}
		if (oldOptions.getBoolean(Config.DEFAULT_CLEAR_REWARDS)) {
			rewards.clear();
		}
		if(oldOptions.getBoolean(Config.DEFAULT_KEEP_TICKETS)) {
			for(String player : getPlayers()) {
				newOptions.set("players." + player, oldOptions.getInt("players." + player, 0));
			}
		}
	}

	public void save() {
		options.set("save-time", timer.getTime());
		options.set("reset-time", timer.getResetTime());
		options.remove("drawing");
		int cntr = 1;
		for(Sign sign : signs) {
			options.set("sign" + cntr++, Utils.parseLocation(sign.getLocation()));
		}
	}
	
	public boolean registerSign(Sign sign) {
		if(hasRegisteredSign(sign)) return false;
		signs.add(sign);
		updateSigns();
		return true;
	}
	
	public boolean hasRegisteredSign(Block block) {
		for(Sign s : signs) {
			if(Plugin.locsInBounds(block.getLocation(), s.getLocation())) {
				return true;
			}
		}
		return false;
	}
	
	public boolean hasRegisteredSign(Sign sign) {
		for(Sign s : signs) {
			if(Plugin.locsInBounds(sign.getLocation(), s.getLocation())) {
				return true;
			}
		}
		return false;
	}
	
	public boolean unregisterSign(Sign sign) {
		for(int cntr = 0;cntr < signs.size();cntr++) {
			Sign s = signs.get(cntr);
			if(Plugin.locsInBounds(sign.getLocation(), s.getLocation())) {
				signs.remove(cntr);
				return true;
			}
		}
		return false;
	}
	
	public void broadcast(String player, int tickets) {
		ChatUtils.broadcast("lottery.mess.buy", "<player>", player, "<tickets>", tickets, "<lottery>", lotteryName);
	}
	
	private boolean canBuy(Player player, int tickets) {
		if (isDrawing() && !Config.getBoolean(Config.BUY_DURING_DRAW)) {
			ChatUtils.sendRaw(player, "lottery.error.drawing");
			return false;
		}
		int ticketLimit = options.getInt(Config.DEFAULT_TICKET_LIMIT);
		if (ticketLimit > 0 && getPlayers().size() >= ticketLimit) {
			ChatUtils.sendRaw(player, "lottery.error.tickets.soldout");
			return false;
		}
		String name = player.getName();
		int maxTickets = options.getInt(Config.DEFAULT_MAX_TICKETS);
		int maxPlayers = options.getInt(Config.DEFAULT_MAX_PLAYERS);
		int playersEntered = getPlayersEntered();
		int num = getTicketsBought(name);
		if (maxTickets > 0) {
			if (num >= maxTickets) {
				ChatUtils.sendRaw(player, "lottery.error.tickets.anymore");
				return false;
			} else if (num + tickets > maxTickets) {
				ChatUtils.sendRaw(player, "lottery.error.tickets.toomuch");
				return false;
			}
		}
		if(maxPlayers > 0) {
			if (playersEntered >= maxPlayers) {
				ChatUtils.sendRaw(player, "lottery.error.players.nomore");
				return false;
			}
		}
		if(tickets <= 0) {
			ChatUtils.sendRaw(player, "lottery.error.tickets.negative");
			return false;
		}
		synchronized(cooldowns) {
			if(cooldowns.containsKey(name)) {
				ChatUtils.sendRaw(player, "lottery.error.cooldown", "<time>", cooldowns.get(name));
				return false;
			}
		}
		return true;
	}

	public synchronized boolean buyTickets(Player player, int tickets) {
		String name = player.getName();
		if (!canBuy(player, tickets)) {
			return false;
		}
		if (!econ.hasAccount(name)) {
			ChatUtils.sendRaw(player, "lottery.error.noaccount");
			return false;
		}
		String taxAccount = options.getString(Config.DEFAULT_TAX_ACCOUNT);
		double ticketCost = options.getDouble(Config.DEFAULT_TICKET_COST);
		double total = ticketCost * (double) tickets;
		double ticketTax = options.getDouble(Config.DEFAULT_TICKET_TAX);
		double add = ticketCost - (ticketCost * (ticketTax / 100));
		double d = add * (double) tickets;
		double taxes = total - d;
		if (!econ.hasEnough(name, total)) {
			ChatUtils.sendRaw(player, "lottery.error.tickets.notenough");
			return false;
		}
		econ.withdraw(name, total);
		if(taxAccount != null && econ.hasAccount(taxAccount)) {
			econ.deposit(taxAccount, taxes);
		}
		int num = options.getInt("players." + name, 0);
		options.set("players." + name, num + tickets);
		ChatUtils.sendRaw(player, "lottery.tickets.mess", "<tickets>", tickets, "<lottery>", lotteryName);
		if (!isItemOnly()) {
			ChatUtils.sendRaw(player, "lottery.pot.mess", "<money>", econ.format(d), "<lottery>", lotteryName);
			options.set(Config.DEFAULT_POT,
					options.getDouble(Config.DEFAULT_POT) + d);
		}
		long cooldown = options.getLong(Config.DEFAULT_COOLDOWN);
		long warmup = options.getLong(Config.DEFAULT_WARMUP);
		long time = timer.getTime() - cooldown + warmup;
		timer.setTime(time);
		long delay = Config.getLong(Config.BUY_DELAY);
		if(delay >= 0) {
			synchronized(cooldowns) {
				cooldowns.put(name, delay);
			}
		}
		updateSigns();
		return true;
	}

	public synchronized boolean rewardPlayer(CommandSender rewarder, String player,
			int tickets) {
		int ticketLimit = options.getInt(Config.DEFAULT_TICKET_LIMIT);
		int num = options.getInt("players." + player, 0);
		if (ticketLimit > 0) {
			int players = getPlayers().size();
			if (players >= ticketLimit) {
				ChatUtils.send(rewarder, "lottery.error.tickets.soldout", "<lottery>", lotteryName);
				return false;
			}
			if (tickets + num > ticketLimit) {
				ChatUtils.send(rewarder, "plugin.command.reward.error.toomany");
				return false;
			}
		}
		options.set("players." + player, num + tickets);
		Player p = Bukkit.getPlayer(player);
		if (p != null) {
			ChatUtils.send(p, "plugin.command.reward.mess", "<tickets>", tickets, "<lottery>", lotteryName);
		}
		updateSigns();
		return true;
	}

	public boolean isOver() {
		int ticketLimit = options.getInt(Config.DEFAULT_TICKET_LIMIT);
		if(ticketLimit <= 0) {
			return false;
		}
		int players = getPlayers().size();
		if(players < ticketLimit) {
			return false;
		}
		int minPlayers = options.getInt(Config.DEFAULT_MIN_PLAYERS);
		if(minPlayers <= 0) return false;
		int entered = getPlayersEntered();
		return entered >= minPlayers && entered >= 1;
	}

	public void sendInfo(CommandSender sender) {
		ChatUtils.sendRaw(sender, "lottery.info.time", "<time>", timer.format());
		ChatUtils.sendRaw(sender, "lottery.info.drawing", "<is_drawing>", isDrawing());
		if (!isItemOnly()) {
			ChatUtils.sendRaw(sender, "lottery.info.pot", "<pot>", econ.format(options.getDouble(Config.DEFAULT_POT)));
		}
		for (Reward reward : rewards) {
			ChatUtils.sendRaw(sender, "lottery.info.reward", "<reward>", reward.getInfo());
		}
		ChatUtils.sendRaw(sender, "lottery.info.ticket-cost", "<ticket_cost>", econ.format(options.getDouble(Config.DEFAULT_TICKET_COST)));
		ChatUtils.sendRaw(sender, "lottery.info.ticket-tax", "<ticket_tax>", String.format("%,.2f", options.getDouble(Config.DEFAULT_TICKET_TAX)));
		ChatUtils.sendRaw(sender, "lottery.info.pot-tax", "<pot_tax>", String.format("%,.2f", options.getDouble(Config.DEFAULT_POT_TAX)));
		ChatUtils.sendRaw(sender, "lottery.info.players", "<players>", getPlayersEntered());
		ChatUtils.sendRaw(sender, "lottery.info.tickets.left", "<number>", formatTicketsLeft());
		if (sender instanceof Player)
			ChatUtils.sendRaw(sender, "lottery.info.tickets.bought", "<number>", getTicketsBought(sender.getName()));
	}

	private String formatTicketsLeft() {
		int ticketLimit = options.getInt(Config.DEFAULT_TICKET_LIMIT);
		if (ticketLimit <= 0)
			return "no limit";
		int left = ticketLimit - getPlayers().size();
		return (left > 0) ? "" + left : "none";
	}

	public int getPlayersEntered() {
		Set<String> players = new HashSet<String>();
		for (String key : options.keySet()) {
			if (key.startsWith("players.")) {
				int index = key.indexOf('.');
				String player = key.substring(index + 1);
				int num = options.getInt(key, 0);
				for (int cntr = 0; cntr < num; cntr++) {
					players.add(player);
				}
			}
		}
		return players.size();
	}

	public List<String> getPlayers() {
		List<String> players = new ArrayList<String>();
		for (String key : options.keySet()) {
			if (key.startsWith("players.")) {
				int index = key.indexOf('.');
				String player = key.substring(index + 1);
				int num = options.getInt(key, 0);
				for (int cntr = 0; cntr < num; cntr++) {
					players.add(player);
				}
			}
		}
		return players;
	}

	public int getTicketsBought(String name) {
		return options.getInt("players." + name, 0);
	}

	public long getTime() {
		return timer.getTime();
	}

	public void draw() {
		draw(null);
	}

	// sender: sender that initiated force draw, may be null if drawing was done
	// 'naturally'
	public synchronized void draw(CommandSender sender) {
		if (options.getBoolean("drawing", false)) {
			if (sender != null) {
				ChatUtils.send(sender, "lottery.error.drawing", "<lottery>", lotteryName);
			}
			return;
		}
		if (sender == null) {
			ChatUtils.broadcast("lottery.drawing.mess", "<lottery>", lotteryName);
		} else {
			ChatUtils.broadcast("lottery.drawing.force.mess", "<lottery>", lotteryName, "<player>", sender.getName());
		}
		long delay = Config.getLong(Config.DRAW_DELAY);
		drawId = Plugin.scheduleAsyncDelayedTask(this, delay * SERVER_SECOND);
		timer.setRunning(false);
		options.set("drawing", true);
		updateSigns();
	}

	public synchronized void cancelDrawing() {
		Plugin.cancelTask(drawId);
	}

	private void clearPlayers() {
		List<String> keys = new ArrayList<String>(options.keySet());
		for (int cntr = 0; cntr < keys.size(); cntr++) {
			String key = keys.get(cntr);
			if (key.startsWith("players.")) {
				options.remove(key);
			}
		}
	}

	public void run() {
		try {
			synchronized(cooldowns) {
				cooldowns.clear();
			}
			drawId = -1;
			List<String> players = this.getPlayers();
			int len = players.size();
			if (len < options.getInt(Config.DEFAULT_MIN_PLAYERS) || len < 1) {
				ChatUtils.broadcast("lottery.error.drawing.notenough");
				resetData();
				options.set("drawing", false);
				return;
			}
			String winner = pickRandomPlayer(rand, players,
					options.getInt(Config.DEFAULT_TICKET_LIMIT));
			if (winner == null) {
				ChatUtils.broadcast("lottery.error.drawing.nowinner");
				options.set("drawing", false);
				success = false;
				LotteryManager.reloadLottery(lotteryName);
				return;
			}
			options.set("winner", winner);
			ChatUtils.broadcast("lottery.drawing.winner.mess", "<winner>", winner);
			if (!this.isItemOnly()) {
				double pot = options.getDouble(Config.DEFAULT_POT);
				double potTax = options.getDouble(Config.DEFAULT_POT_TAX);
				double winnings = pot - (pot * (potTax / 100));
				rewards.add(0, new PotReward(econ, winnings));
			}
			StringBuilder logWinner = new StringBuilder(lotteryName + ": " + winner);
			for (Reward reward : rewards) {
				logWinner.append(", ");
				logWinner.append("[" + reward.getInfo() + "]");
			}
			clearPlayers();
			WinnersManager.logWinner(logWinner.toString());
			Player pWinner = Bukkit.getPlayer(winner);

			if (pWinner != null) {
				handleRewards(rewards, pWinner);
			} else {
				ClaimManager.addClaim(winner, lotteryName, rewards);
			}
			drawId = -1;
			options.set("drawing", false);
			success = true;
			if (options.getBoolean(Config.DEFAULT_REPEAT)) {
				LotteryManager.reloadLottery(lotteryName);
			} else {
				LotteryManager.unloadLottery(lotteryName);
				updateSigns(true);
			}
		} catch (Exception ex) {
			Logger.info("lottery.exception.drawing", "<lottery>", lotteryName);
			options.set("drawing", false);
			success = false;
			ex.printStackTrace();
		}
	}

	private static String pickRandomPlayer(Random rand, List<String> players,
			int ticketLimit) {
		Collections.shuffle(players, rand);
		if (ticketLimit <= 0) {
			return players.get(rand.nextInt(players.size()));
		} else {
			List<Integer> spots = new ArrayList<Integer>();
			for(int i = 0;i < ticketLimit;i++)
				spots.add(i);
			Map<Integer, String> map = new HashMap<Integer, String>();
			while(!players.isEmpty()) {
				int index = spots.remove(rand.nextInt(spots.size()));
				String player = players.remove(rand.nextInt(players.size()));
				map.put(index, player);
			}
			int winningNumber = rand.nextInt(ticketLimit);
			return map.get(winningNumber);
		}
	}

	public static void handleRewards(List<Reward> rewards, Player player) {
		for (Reward reward : rewards) {
			reward.rewardPlayer(player);
		}
	}

	private void resetData() {
		double time = options.getDouble(Config.DEFAULT_RESET_ADD_TIME);
		long t = (long) Math.floor(time * (double)HOUR);
		this.timer.setResetTime(timer.getResetTime() + t);
		options.set(
				Config.DEFAULT_TICKET_COST,
				options.getDouble(Config.DEFAULT_TICKET_COST)
						+ options
								.getDouble(Config.DEFAULT_RESET_ADD_TICKET_COST));
		options.set(Config.DEFAULT_POT, options.getDouble(Config.DEFAULT_POT)
				+ options.getDouble(Config.DEFAULT_RESET_ADD_POT));
		options.set(
				Config.DEFAULT_COOLDOWN,
				options.getLong(Config.DEFAULT_COOLDOWN)
						+ options.getLong(Config.DEFAULT_RESET_ADD_COOLDOWN));
		options.set(
				Config.DEFAULT_WARMUP,
				options.getLong(Config.DEFAULT_WARMUP)
						+ options.getLong(Config.DEFAULT_RESET_ADD_WARMUP));
		options.set(
				Config.DEFAULT_MAX_TICKETS,
				options.getInt(Config.DEFAULT_MAX_TICKETS)
						+ options.getInt(Config.DEFAULT_RESET_ADD_MAX_TICKETS));
		options.set(
				Config.DEFAULT_MAX_PLAYERS,
				options.getInt(Config.DEFAULT_MAX_PLAYERS)
						+ options.getInt(Config.DEFAULT_RESET_ADD_MAX_PLAYERS));
		options.set(
				Config.DEFAULT_MIN_PLAYERS,
				options.getInt(Config.DEFAULT_MIN_PLAYERS)
						+ options.getInt(Config.DEFAULT_RESET_ADD_MIN_PLAYERS));
		options.set(
				Config.DEFAULT_TICKET_TAX,
				options.getDouble(Config.DEFAULT_TICKET_TAX)
						+ options
								.getDouble(Config.DEFAULT_RESET_ADD_TICKET_TAX));
		options.set(
				Config.DEFAULT_POT_TAX,
				options.getDouble(Config.DEFAULT_POT_TAX)
						+ options.getDouble(Config.DEFAULT_RESET_ADD_POT_TAX));
		String read = options.getString(Config.DEFAULT_RESET_ADD_ITEM_REWARDS);
		if(read != null && !read.isEmpty()) {
			for(ItemStack item : Utils.getItemStacks(read)) {
				rewards.add(new ItemReward(item));
			}
		}
		this.timer.reset();
	}

	public int hashCode() {
		return toString().hashCode();
	}

	public String toString() {
		return lotteryName;
	}
}
