/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//
// Interface to the A2DP Opus Encoder
//

#ifndef A2DP_VENDOR_OPUS_ENCODER_H
#define A2DP_VENDOR_OPUS_ENCODER_H

#include "a2dp_codec_api.h"

// Initialize the A2DP Opus encoder.
// |p_peer_params| contains the A2DP peer information
// The current A2DP codec config is in |a2dp_codec_config|.
// |read_callback| is the callback for reading the input audio data.
// |enqueue_callback| is the callback for enqueueing the encoded audio data.
void a2dp_vendor_opus_encoder_init(const tA2DP_ENCODER_INIT_PEER_PARAMS* p_peer_params,
                                   A2dpCodecConfig* a2dp_codec_config,
                                   a2dp_source_read_callback_t read_callback,
                                   a2dp_source_enqueue_callback_t enqueue_callback);

// Cleanup the A2DP Opus encoder.
void a2dp_vendor_opus_encoder_cleanup(void);

// Reset the feeding for the A2DP Opus encoder.
void a2dp_vendor_opus_feeding_reset(void);

// Flush the feeding for the A2DP Opus encoder.
void a2dp_vendor_opus_feeding_flush(void);

// Get the A2DP Opus encoder interval (in milliseconds).
uint64_t a2dp_vendor_opus_get_encoder_interval_ms(void);

// Prepare and send A2DP Opus encoded frames.
// |timestamp_us| is the current timestamp (in microseconds).
void a2dp_vendor_opus_send_frames(uint64_t timestamp_us);

// Set transmit queue length for the A2DP Opus (Dynamic Bit Rate) mechanism.
void a2dp_vendor_opus_set_transmit_queue_length(size_t transmit_queue_length);

// Get the A2DP Opus encoded maximum frame size
int a2dp_vendor_opus_get_effective_frame_size();

#endif  // A2DP_VENDOR_OPUS_ENCODER_H
