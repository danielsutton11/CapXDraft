# draft_bot.py
import discord
from discord.ext import commands
from discord import app_commands
import os
import json
import random
import asyncio
from typing import List
from dotenv import load_dotenv

# ---------- Load environment variables ----------
load_dotenv()
DISCORD_TOKEN = os.getenv("DISCORD_TOKEN")

# ---------- Config ----------
CAPTAIN_ROLE_ID = 1422656496088514632  # Replace with your Captain role ID
ELIGIBLE_ROLE_ID = 989342758399512606  # Replace with your Participants role ID
STATE_FILE = "draft_state.json"

# ---------- Bot setup ----------
intents = discord.Intents.default()
intents.guilds = True
intents.members = True
bot = commands.Bot(command_prefix="!", intents=intents)
state_lock = asyncio.Lock()
draft_state = {}  # in-memory cache

# ---------- Helpers ----------
def save_state():
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(draft_state, f, indent=2)

def load_state():
    global draft_state
    try:
        with open(STATE_FILE, "r", encoding="utf-8") as f:
            draft_state = json.load(f)
    except FileNotFoundError:
        draft_state = {}

def member_display(m: discord.Member):
    return m.display_name

async def members_with_role(guild: discord.Guild, role_id: int) -> List[discord.Member]:
    role = guild.get_role(role_id)
    if role and role.members:
        return list(role.members)
    return [m async for m in guild.fetch_members(limit=None) if any(r.id == role_id for r in m.roles)]

def team_colors():
    return [
        discord.Color.blue(),
        discord.Color.green(),
        discord.Color.orange(),
        discord.Color.purple(),
        discord.Color.red(),
        discord.Color.gold(),
        discord.Color.teal(),
        discord.Color.dark_magenta(),
    ]

# ---------- Embeds ----------
def dump_teams_embeds(guild_id: str) -> List[discord.Embed]:
    s = draft_state.get(str(guild_id))
    if not s:
        return []

    embeds = []
    colors = team_colors()
    for i, cid in enumerate(s["captain_order"]):
        pname = s["captain_names"].get(str(cid), str(cid))
        picks = s["picks"].get(str(cid), [])
        # use description (4096 chars) to avoid field size limits
        description = "\n".join([f"- {p['display']} (Round {p['round']}, Pick {p['pick_number']})" for p in picks])
        if not description:
            description = "*(no picks yet)*"
        embed = discord.Embed(title=pname, description=description, color=colors[i % len(colors)])
        embeds.append(embed)
    return embeds

# ---------- Bot events ----------
@bot.event
async def on_ready():
    print(f"Logged in as {bot.user} (id: {bot.user.id})")
    await bot.tree.sync()
    print("Commands synced globally")

# ---------- Draft commands ----------
@bot.tree.command(name="startdraft", description="Start a live draft (admins only)")
async def startdraft(interaction: discord.Interaction):
    if not interaction.guild:
        await interaction.response.send_message("This command must be used in a server.", ephemeral=True)
        return
    if not interaction.user.guild_permissions.administrator:
        await interaction.response.send_message("Admin only.", ephemeral=True)
        return

    await interaction.response.defer(ephemeral=False)
    guild = interaction.guild
    captains = await members_with_role(guild, CAPTAIN_ROLE_ID)
    if not captains:
        await interaction.followup.send("No captains found.")
        return
    eligible_members = await members_with_role(guild, ELIGIBLE_ROLE_ID)
    # remove captains from eligible pool
    eligible_filtered = [m for m in eligible_members if m.id not in {c.id for c in captains}]
    if not eligible_filtered:
        await interaction.followup.send("No eligible members to draft.")
        return

    # Randomize captain order
    captain_order = captains[:]
    random.shuffle(captain_order)

    # Snake draft order (list of captain ids repeated per pick slot)
    num_picks = len(eligible_filtered)
    num_caps = len(captain_order)
    draft_seq = []
    rounds = (num_picks + num_caps - 1) // num_caps
    for r in range(rounds):
        seq = [c.id for c in captain_order] if r % 2 == 0 else [c.id for c in reversed(captain_order)]
        draft_seq.extend(seq)
    draft_seq = draft_seq[:num_picks]

    # Save draft state - NOTE: use current_pick_index consistently
    s = {
        "active": True,
        "captain_order": [c.id for c in captain_order],
        "captain_names": {str(c.id): member_display(c) for c in captain_order},
        "draft_order": draft_seq,
        "eligible": [{"id": m.id, "display": member_display(m)} for m in eligible_filtered],
        "picks": {str(c.id): [] for c in captain_order},
        "queues": {str(c.id): [] for c in captain_order},
        "current_pick_index": 0,
        "channel_id": interaction.channel.id,
    }
    draft_state[str(guild.id)] = s
    save_state()

    embeds = dump_teams_embeds(str(guild.id))
    await interaction.followup.send(content=f"Draft started! {len(eligible_filtered)} eligible members, {len(captain_order)} captains.", embeds=embeds)

    # start the draft by processing first turn(s)
    await process_captain_turn(str(guild.id))

# ---------- Autocomplete ----------
async def pick_autocomplete(interaction: discord.Interaction, current: str):
    s = draft_state.get(str(interaction.guild.id), {})
    eligible = s.get("eligible", [])
    return [
        app_commands.Choice(name=m["display"], value=str(m["id"]))
        for m in eligible if current.lower() in m["display"].lower()
    ][:25]

async def queue_autocomplete(interaction: discord.Interaction, current: str):
    guild_id = str(interaction.guild.id)
    s = draft_state.get(guild_id, {})
    eligible = s.get("eligible", [])
    return [
        app_commands.Choice(name=m["display"], value=str(m["id"]))
        for m in eligible if current.lower() in m["display"].lower()
    ][:25]

# ---------- /pick ----------
@bot.tree.command(name="pick", description="Make your draft pick (autocomplete enabled)")
@app_commands.describe(member_query="Start typing a name or choose from suggestions")
@app_commands.autocomplete(member_query=pick_autocomplete)
async def pick(interaction: discord.Interaction, member_query: str):
    await handle_pick(interaction, manual_query=member_query)

# ---------- /queue ----------
@bot.tree.command(name="queue", description="Queue up to 5 picks (captains only)")
@app_commands.describe(member_query="Type member name to add to your queue")
@app_commands.autocomplete(member_query=queue_autocomplete)
async def queue(interaction: discord.Interaction, member_query: str):
    guild_id = str(interaction.guild.id)

    async with state_lock:
        s = draft_state.get(guild_id)
        if not s or not s.get("active"):
            await interaction.response.send_message("No active draft.", ephemeral=True)
            return
        if interaction.user.id not in s["captain_order"]:
            await interaction.response.send_message("Only captains can queue.", ephemeral=True)
            return

        chosen_member = next((e for e in s["eligible"] if str(e["id"]) == member_query), None)
        if not chosen_member:
            await interaction.response.send_message("Member not eligible.", ephemeral=True)
            return

        queue_list = s["queues"].get(str(interaction.user.id), [])
        if chosen_member["id"] in queue_list:
            await interaction.response.send_message(f"{chosen_member['display']} is already queued.", ephemeral=True)
            return

        if len(queue_list) >= 5:
            await interaction.response.send_message("Queue full (5 max).", ephemeral=True)
            return

        queue_list.append(chosen_member["id"])
        s["queues"][str(interaction.user.id)] = queue_list
        save_state()

        await interaction.response.send_message(f"Queued {chosen_member['display']} ({len(queue_list)}/5)", ephemeral=True)

    # --- AFTER LOCK: handle immediate pick if it's this captain's turn and queue was empty ---
    async with state_lock:
        s = draft_state.get(guild_id)
        current_idx = s.get("current_pick_index", 0)
        draft_order = s.get("draft_order", [])
        if current_idx >= len(draft_order):
            return  # draft already finished
        if draft_order[current_idx] != interaction.user.id:
            return  # not this captain's turn

        # Only pick if queue has members
        queue_list = s["queues"].get(str(interaction.user.id), [])
        if queue_list:
            # Pick the first queued member
            member_to_pick = queue_list.pop(0)
            candidate = next((e for e in s["eligible"] if e["id"] == member_to_pick), None)
            if candidate:
                idx = s["current_pick_index"]
                round_number = (idx // len(s["captain_order"])) + 1
                pick_number = idx + 1
                s["picks"].setdefault(str(interaction.user.id), []).append({
                    "id": candidate["id"],
                    "display": candidate["display"],
                    "round": round_number,
                    "pick_number": pick_number
                })
                s["eligible"] = [e for e in s["eligible"] if e["id"] != candidate["id"]]
                s["current_pick_index"] = idx + 1
                s["queues"][str(interaction.user.id)] = queue_list
                save_state()

                channel = bot.get_channel(s["channel_id"])
                if channel:
                    try:
                        await channel.send(f"<@{interaction.user.id}> auto-picked **{candidate['display']}** from their queue (pick {pick_number}).")
                    except discord.Forbidden:
                        await channel.send(f"{candidate['display']} was auto-picked for captain {s['captain_names'].get(str(interaction.user.id), str(interaction.user.id))} (pick {pick_number}).")

                # Continue the draft
                await process_captain_turn(guild_id)



# --------- Auto pick from queue --------
async def auto_pick_from_queue(guild_id: str, captain_id: int, channel: discord.TextChannel):
    """Try to auto-pick the first available queued player for a captain.
       Returns the chosen member dict or None.
       This function acquires the state_lock internally.
    """
    async with state_lock:
        s = draft_state.get(guild_id)
        if not s or not s.get("active"):
            return None

        queue_list = s["queues"].get(str(captain_id), [])
        if not queue_list:
            return None

        # Loop through queued IDs until we find one still eligible
        chosen = None
        for player_id in list(queue_list):  # copy so we can remove safely while iterating
            candidate = next((e for e in s["eligible"] if e["id"] == player_id), None)
            if candidate:
                chosen = candidate
                # remove from eligible
                s["eligible"] = [e for e in s["eligible"] if e["id"] != player_id]
                # remove this id from queue_list
                queue_list.remove(player_id)
                s["queues"][str(captain_id)] = queue_list
                # Record pick in picks with round/pick_number
                idx = s["current_pick_index"]
                round_number = (idx // len(s["captain_order"])) + 1
                pick_number = idx + 1
                s["picks"].setdefault(str(captain_id), []).append({
                    "id": candidate["id"],
                    "display": candidate["display"],
                    "round": round_number,
                    "pick_number": pick_number
                })
                s["current_pick_index"] = idx + 1
                save_state()
                # announce in channel
                try:
                    await channel.send(f"<@{captain_id}> auto-picked **{candidate['display']}** from their queue (pick {pick_number}).")
                except discord.Forbidden:
                    # fallback to plain text if mention not allowed
                    await channel.send(f"{candidate['display']} was auto-picked for captain {s['captain_names'].get(str(captain_id), str(captain_id))} (pick {pick_number}).")
                return candidate

        # If no eligible queued players remain, clear queue (optional)
        # (we already removed any processed items above)
        s["queues"][str(captain_id)] = queue_list
        save_state()
        return None

# ---------- /cancelqueue ----------
@bot.tree.command(name="cancelqueue", description="Clear your draft queue (captains only)")
async def cancelqueue(interaction: discord.Interaction):
    guild_id = str(interaction.guild.id)
    async with state_lock:
        s = draft_state.get(guild_id)
        if not s or not s.get("active"):
            await interaction.response.send_message("No active draft.", ephemeral=True)
            return
        if interaction.user.id not in s["captain_order"]:
            await interaction.response.send_message("Only captains can cancel queues.", ephemeral=True)
            return

        s["queues"][str(interaction.user.id)] = []
        save_state()
        await interaction.response.send_message("Your draft queue has been cleared.", ephemeral=True)

# ---------- /remainingdraftees ----------
@bot.tree.command(name="remainingdraftees", description="Show all remaining eligible players")
async def remainingdraftees(interaction: discord.Interaction):
    guild_id = str(interaction.guild.id)
    s = draft_state.get(guild_id)
    if not s or not s.get("active"):
        await interaction.response.send_message("No active draft.", ephemeral=True)
        return

    eligible = s.get("eligible", [])
    if not eligible:
        await interaction.response.send_message("No remaining players to draft.", ephemeral=True)
        return

    # chunk the list to avoid huge descriptions
    chunks = [eligible[i:i+40] for i in range(0, len(eligible), 40)]
    embeds = []
    for i, chunk in enumerate(chunks, 1):
        desc = "\n".join([f"- {e['display']}" for e in chunk])
        embed = discord.Embed(title=f"Remaining Draftees (Page {i})", description=desc, color=discord.Color.blurple())
        embeds.append(embed)

    # respond ephemeral so only the caller sees their remaining pool
    await interaction.response.send_message("Remaining players:", embeds=embeds, ephemeral=True)

# ---------- Pick handler ----------
async def handle_pick(interaction: discord.Interaction, manual_query: str = None):
    if not interaction.guild:
        await interaction.response.send_message("Server-only command.", ephemeral=True)
        return
    guild_id = str(interaction.guild.id)
    async with state_lock:
        s = draft_state.get(guild_id)
        if not s or not s.get("active"):
            await interaction.response.send_message("No active draft.", ephemeral=True)
            return
        idx = s["current_pick_index"]
        if idx >= len(s["draft_order"]):
            await interaction.response.send_message("Draft finished.", ephemeral=True)
            return

        expected_captain = s["draft_order"][idx]
        if interaction.user.id != expected_captain:
            await interaction.response.send_message("Not your turn.", ephemeral=True)
            return

        # Clean their queue (remove non-eligible entries)
        queue = s["queues"].get(str(expected_captain), [])
        queue = [mid for mid in queue if any(e["id"] == mid for e in s["eligible"])]
        s["queues"][str(expected_captain)] = queue

        chosen_member = None
        # Try queued pick first
        if queue:
            mid = queue.pop(0)
            s["queues"][str(expected_captain)] = queue
            chosen_member = next((e for e in s["eligible"] if e["id"] == mid), None)

        # Otherwise manual pick via argument
        if not chosen_member and manual_query:
            if manual_query.isdigit():
                chosen_member = next((e for e in s["eligible"] if e["id"] == int(manual_query)), None)
            if not chosen_member:
                matches = [e for e in s["eligible"] if manual_query.lower() in e["display"].lower()]
                if len(matches) == 1:
                    chosen_member = matches[0]
                elif len(matches) > 1:
                    await interaction.response.send_message("Multiple matches, refine query.", ephemeral=True)
                    return
            if not chosen_member:
                await interaction.response.send_message("Member not eligible.", ephemeral=True)
                return

        if not chosen_member:
            await interaction.response.send_message("No queued members or valid manual pick.", ephemeral=True)
            return

        # Record pick
        round_number = (idx // len(s["captain_order"])) + 1
        pick_number = idx + 1
        s["picks"].setdefault(str(expected_captain), []).append({
            "id": chosen_member["id"],
            "display": chosen_member["display"],
            "round": round_number,
            "pick_number": pick_number
        })
        s["eligible"] = [e for e in s["eligible"] if e["id"] != chosen_member["id"]]
        s["current_pick_index"] = idx + 1
        save_state()

        # respond publicly so the draft channel sees the pick
        await interaction.response.send_message(f"{interaction.user.mention} picked **{chosen_member['display']}** (pick {pick_number})")

    # After releasing the lock, continue with next captain
    await process_captain_turn(guild_id)

# ---------- Process captain turn ----------
async def process_captain_turn(guild_id: str):
    """Handles moving through draft_order, auto-picking from queues if present, or pinging captain."""
    s = draft_state.get(guild_id)
    if not s or not s.get("active"):
        return

    # If finished, publish final teams
    if s["current_pick_index"] >= len(s["draft_order"]):
        s["active"] = False
        save_state()
        channel = bot.get_channel(s["channel_id"])
        embeds = dump_teams_embeds(guild_id)
        await channel.send("Draft complete! Final teams:", embeds=embeds)
        return

    # determine next captain and attempt to auto-pick from their queue
    next_cid = s["draft_order"][s["current_pick_index"]]
    channel = bot.get_channel(s["channel_id"])
    member = channel.guild.get_member(next_cid) if channel and channel.guild else None

    # Clean queue and update state
    queue = s["queues"].get(str(next_cid), [])
    queue = [mid for mid in queue if any(e["id"] == mid for e in s["eligible"])]
    s["queues"][str(next_cid)] = queue
    save_state()

    # If queue has eligible entry, auto-pick the first one
    if queue:
        mid = queue.pop(0)
        s["queues"][str(next_cid)] = queue
        chosen_member = next((e for e in s["eligible"] if e["id"] == mid), None)
        if chosen_member:
            idx = s["current_pick_index"]
            round_number = (idx // len(s["captain_order"])) + 1
            pick_number = idx + 1
            s["picks"].setdefault(str(next_cid), []).append({
                "id": chosen_member["id"],
                "display": chosen_member["display"],
                "round": round_number,
                "pick_number": pick_number
            })
            s["eligible"] = [e for e in s["eligible"] if e["id"] != chosen_member["id"]]
            s["current_pick_index"] = idx + 1
            save_state()
            try:
                await channel.send(f"<@{next_cid}> auto-picked **{chosen_member['display']}** from their queue (pick {pick_number}).")
            except discord.Forbidden:
                await channel.send(f"{chosen_member['display']} was auto-picked for captain {s['captain_names'].get(str(next_cid), str(next_cid))} (pick {pick_number}).")
            # recurse to process the next pick (handles chains of queued picks)
            await process_captain_turn(guild_id)
            return

    # No queued eligible pick — ping captain to make a manual pick
    if member:
        try:
            await channel.send(f"{member.mention}, it's your pick now. Use /pick and start typing the member's name.")
        except discord.Forbidden:
            await channel.send(f"It's {member_display(member)}'s turn to pick (bot lacks permission to mention).")

# ---------- /checkteams ----------
@bot.tree.command(name="checkteams", description="Display current teams / picks")
async def checkteams(interaction: discord.Interaction):
    guild_id = str(interaction.guild.id)
    embeds = dump_teams_embeds(guild_id)
    if not embeds:
        await interaction.response.send_message("No draft data.", ephemeral=True)
        return

    # Discord allows up to 10 embeds per message. Send in chunks if needed.
    max_embeds = 10
    for i in range(0, len(embeds), max_embeds):
        chunk = embeds[i:i+max_embeds]
        if i == 0:
            await interaction.response.send_message("Current teams:", embeds=chunk)
        else:
            await interaction.followup.send(embeds=chunk)

# ---------- /enddraft ----------
@bot.tree.command(name="enddraft", description="End the draft (admins only)")
async def enddraft(interaction: discord.Interaction):
    if not interaction.guild:
        await interaction.response.send_message("Server only.", ephemeral=True)
        return
    if not interaction.user.guild_permissions.administrator:
        await interaction.response.send_message("Admin only.", ephemeral=True)
        return

    guild_id = str(interaction.guild.id)
    s = draft_state.get(guild_id)
    if not s or not s.get("active"):
        await interaction.response.send_message("No active draft.", ephemeral=True)
        return

    view = discord.ui.View(timeout=30)
    async def confirm(button_interaction: discord.Interaction):
        if button_interaction.user.id != interaction.user.id:
            await button_interaction.response.send_message("Only the admin can confirm.", ephemeral=True)
            return
        async with state_lock:
            s["active"] = False
            save_state()
        embeds = dump_teams_embeds(guild_id)
        await button_interaction.response.edit_message(content="Draft ended. Final teams:", embeds=embeds, view=None)

    async def cancel(button_interaction: discord.Interaction):
        if button_interaction.user.id != interaction.user.id:
            await button_interaction.response.send_message("Only the admin can cancel.", ephemeral=True)
            return
        await button_interaction.response.edit_message(content="Cancelled.", view=None)

    yes_button = discord.ui.Button(label="Yes, end draft", style=discord.ButtonStyle.danger)
    yes_button.callback = confirm
    no_button = discord.ui.Button(label="No, keep draft", style=discord.ButtonStyle.secondary)
    no_button.callback = cancel
    view.add_item(yes_button)
    view.add_item(no_button)
    await interaction.response.send_message("End draft?", view=view)

# ---------- Run ----------
if __name__ == "__main__":
    if not DISCORD_TOKEN:
        print("Set DISCORD_TOKEN")
    else:
        load_state()
        bot.run(DISCORD_TOKEN)
