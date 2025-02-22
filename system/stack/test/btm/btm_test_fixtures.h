/*
 * Copyright 2024 The Android Open Source Project
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

#pragma once

#include <gtest/gtest.h>

#include "test/common/mock_functions.h"
#include "test/fake/fake_osi.h"
#include "test/mock/mock_stack_rnr_interface.h"

class BtmWithFakesTest : public testing::Test {
protected:
  void SetUp() override { fake_osi_ = std::make_unique<test::fake::FakeOsi>(); }

  void TearDown() override { fake_osi_.reset(); }
  std::unique_ptr<test::fake::FakeOsi> fake_osi_;
};

// Setup any default or optional mocks
class BtmWithMocksTest : public BtmWithFakesTest {
protected:
  void SetUp() override {
    BtmWithFakesTest::SetUp();
    reset_mock_function_count_map();
    bluetooth::testing::stack::rnr::set_interface(&mock_stack_rnr_interface_);
  }

  void TearDown() override { BtmWithFakesTest::TearDown(); }

  bluetooth::testing::stack::rnr::Mock mock_stack_rnr_interface_;
};
