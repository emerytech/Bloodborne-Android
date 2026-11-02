// SPDX-License-Identifier: GPL-2.0-or-later
// bbport tap-to-use: apply a companion-requested item use on the game's own thread.
#pragma once
#include <cstdint>

namespace BbUseItem {
// Called once from bbport_frame_rate::PatchImage with the loaded image base.
void SetImage(std::uint64_t image_base);
// Pumped every frame from the main-loop step hook (StepSlot), on the game thread. Applies a pending
// request (set by bb_use_item_post) by calling the game's use-item native, and records the result.
void Poll();
} // namespace BbUseItem

extern "C" {
// Set by bb-probe (src/runtime_atlas.c) when the atlas.use request file names a new (seq, goods) use.
void bb_use_item_post(std::uint32_t seq, std::int32_t goods_id);
// Read by bb-probe to publish the result to the companion: (u64)seq << 32 | status.
std::uint64_t bb_use_item_ack(void);
}
