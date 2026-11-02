// SPDX-License-Identifier: GPL-2.0-or-later
// bbport tap-to-use: the companion (lower screen) requests using a carried item; the request is
// applied here, ON THE GAME'S OWN THREAD, from the per-frame main-loop step hook (bbport_frame_rate
// StepSlot pumps Poll()). Calling the game's use-item native from any other thread crashes (SEGV,
// confirmed 2026-11-01): a hook-slot callback runs on the game thread and may call guest code
// (docs/RECOMP_ABI.md, "A slot callback may call guest code"). The use-item native is SysV
//   apply(rdi = player, rsi = goods_id, rdx = argument)
// (gpu/bbnet20/bbsc/core/bloodborne_re.cpp: UseItemNativeApplyOffset, WriteUseItemExecution).
//
// bb-probe (src/runtime_atlas.c) reads the atlas.use request file and calls bb_use_item_post(); it
// reads bb_use_item_ack() and publishes it to the companion through the atlas shm.
//
// BB_TAP_TO_USE gates the actual call: unset/0 = log the resolved player/goods only and do not call
// (safe validation of the player pointer on-device); 1 = call the native.
#include "bbport_use_item.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "../../src/guest_cpu.h"

namespace BbUseItem {
namespace {
using u64 = std::uint64_t;
using u32 = std::uint32_t;
using s32 = std::int32_t;

// Image offsets of the 1.09 eboot.
constexpr u64 UseItemNativeApply = 0x018F9720; // apply(player, goods_id, argument)
constexpr s32 BellUseArgument = 17;            // the argument the game passes for a goods use
constexpr u64 WorldChrManSlot = 0x553e878;     // *(image + slot) -> WorldChrMan; +0x60 = player ChrIns

u64 g_image = 0;
std::atomic<u64> g_request{0}; // (u64)seq << 32 | (u32)goods_id ; seq != g_last_seq means pending
std::atomic<u64> g_ack{0};     // (u64)seq << 32 | status
u32 g_last_seq = 0;
int g_call = -1; // BB_TAP_TO_USE cache: 0 log only, 1 call

// Status codes published to the companion.
enum : u32 { StatusLoggedOnly = 0, StatusApplied = 1, StatusBadState = 2, StatusNoPlayer = 3 };

bool call_enabled() {
    if (g_call < 0) {
        const char* v = std::getenv("BB_TAP_TO_USE");
        g_call = (v && v[0] == '0') ? 0 : 1; // on by default; BB_TAP_TO_USE=0 disables the native call
    }
    return g_call == 1;
}
u64 read_u64(u64 at) {
    u64 v = 0;
    std::memcpy(&v, reinterpret_cast<const void*>(at), sizeof(v));
    return v;
}
} // namespace

void SetImage(u64 image_base) { g_image = image_base; }

void Poll() {
    if (g_image == 0) {
        return;
    }
    const u64 req = g_request.load(std::memory_order_acquire);
    const u32 seq = static_cast<u32>(req >> 32);
    if (seq == 0 || seq == g_last_seq) {
        return; // nothing new this frame (the common case)
    }
    g_last_seq = seq;
    const s32 goods_id = static_cast<s32>(static_cast<u32>(req));

    // The use-item native takes the player ChrIns (world+0x60, the same chain the Kit stats walk).
    const u64 world = read_u64(g_image + WorldChrManSlot);
    const u64 player = world ? read_u64(world + 0x60) : 0;

    u32 status;
    if (player < 0x10000) {
        status = StatusNoPlayer;
    } else if (!call_enabled()) {
        status = StatusLoggedOnly; // BB_TAP_TO_USE=0: resolved but not applied
    } else {
        const u64 args[3] = {player, static_cast<u64>(static_cast<u32>(goods_id)),
                             static_cast<u64>(static_cast<u32>(BellUseArgument))};
        guest_cpu_call(g_image + UseItemNativeApply, 3, args);
        status = StatusApplied;
    }
    std::printf("tap-to-use: seq %u goods %d player %#llx status %u\n", seq, goods_id,
                static_cast<unsigned long long>(player), status);
    std::fflush(stdout);
    g_ack.store((static_cast<u64>(seq) << 32) | status, std::memory_order_release);
}

} // namespace BbUseItem

extern "C" __attribute__((visibility("default"))) void bb_use_item_post(std::uint32_t seq,
                                                                        std::int32_t goods_id) {
    BbUseItem::g_request.store((static_cast<std::uint64_t>(seq) << 32) |
                                   static_cast<std::uint32_t>(goods_id),
                               std::memory_order_release);
}

extern "C" __attribute__((visibility("default"))) std::uint64_t bb_use_item_ack(void) {
    return BbUseItem::g_ack.load(std::memory_order_acquire);
}
