/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.gpuvulkan.encoding;

import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.video.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRVideoEncodeH264.*;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.*;
import static org.lwjgl.vulkan.KHRVideoQueue.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.video.STDVulkanVideoCodecH264.*;

/**
 * Owns the {@code VkVideoSessionKHR} and its H.264 SPS/PPS parameters.
 * One instance per dimension / FPS configuration.
 */
@Slf4j
public class H264SessionConfig implements AutoCloseable
{
    private final VulkanDevice vulkanDevice;
    private final VulkanCapabilities caps;
    private final int width;
    private final int height;
    private final int fps;

    private long videoSession = VK_NULL_HANDLE;
    private long sessionParameters = VK_NULL_HANDLE;

    private long[] sessionMemoryAllocations;

    // These structs are passed to the driver and must live for the
    // lifetime of the session parameters.
    private StdVideoH264SequenceParameterSet sps;
    private StdVideoH264PictureParameterSet pps;
    private StdVideoH264SpsFlags spsFlags;
    private StdVideoH264PpsFlags ppsFlags;
    private StdVideoH264SequenceParameterSetVui spsVui;
    private StdVideoH264SpsVuiFlags spsVuiFlags;

    private boolean closed = false;

    public H264SessionConfig(VulkanDevice vulkanDevice, VulkanCapabilities caps, int width, int height, int fps)
    {
        this.vulkanDevice = vulkanDevice;
        this.caps = caps;
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    /** Creates the video session and uploads the SPS/PPS. */
    public void initialize()
    {
        createVideoSession();
        bindSessionMemory();
        createSessionParameters();
    }

    private void createVideoSession()
    {
        try (MemoryStack stack = stackPush())
        {
            VkVideoProfileInfoKHR videoProfile = caps.buildVideoProfile(stack);

            // LWJGL 3.3.2's VkExtensionProperties is read-only; poke the
            // extensionName / specVersion fields via raw memory.
            VkExtensionProperties stdHeader = VkExtensionProperties.calloc(stack);
            ByteBuffer nameBytes = stack.UTF8(VK_STD_VULKAN_VIDEO_CODEC_H264_ENCODE_EXTENSION_NAME);
            MemoryUtil.memCopy(MemoryUtil.memAddress(nameBytes), MemoryUtil.memAddress(stdHeader.extensionName()),
                Math.min(nameBytes.remaining(), stdHeader.extensionName().remaining()));
            MemoryUtil.memPutInt(stdHeader.address() + 256, VK_STD_VULKAN_VIDEO_CODEC_H264_ENCODE_SPEC_VERSION);

            // 2 DPB slots / 1 active ref are the minimum for IPPP ping-pong.
            // Over-declaring against the caps max makes VCN misplace the
            // reference picture and fall back to intra-only.
            VkVideoSessionCreateInfoKHR createInfo = VkVideoSessionCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_SESSION_CREATE_INFO_KHR)
                .queueFamilyIndex(vulkanDevice.getVideoEncodeQueueFamily())
                .pVideoProfile(videoProfile)
                .pictureFormat(caps.getPictureFormat())
                .maxCodedExtent(e -> e.width(width).height(height))
                .referencePictureFormat(caps.getPictureFormat())
                .maxDpbSlots(2)
                .maxActiveReferencePictures(1)
                .pStdHeaderVersion(stdHeader);

            LongBuffer pSession = stack.mallocLong(1);
            int result = vkCreateVideoSessionKHR(vulkanDevice.getDevice(), createInfo, null, pSession);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateVideoSessionKHR failed: " + result);
            }
            videoSession = pSession.get(0);
        }
    }

    private void bindSessionMemory()
    {
        VkDevice device = vulkanDevice.getDevice();

        try (MemoryStack stack = stackPush())
        {
            // Query how many memory bindings the session needs
            IntBuffer pCount = stack.mallocInt(1);
            vkGetVideoSessionMemoryRequirementsKHR(device, videoSession, pCount, null);
            int count = pCount.get(0);

            if (count == 0)
            {
                return;
            }

            VkVideoSessionMemoryRequirementsKHR.Buffer memReqs =
                VkVideoSessionMemoryRequirementsKHR.calloc(count, stack);
            for (int i = 0; i < count; i++)
            {
                memReqs.get(i).sType(VK_STRUCTURE_TYPE_VIDEO_SESSION_MEMORY_REQUIREMENTS_KHR);
            }
            vkGetVideoSessionMemoryRequirementsKHR(device, videoSession, pCount, memReqs);

            // Allocate and bind memory for each requirement
            sessionMemoryAllocations = new long[count];
            VkBindVideoSessionMemoryInfoKHR.Buffer bindInfos =
                VkBindVideoSessionMemoryInfoKHR.calloc(count, stack);

            for (int i = 0; i < count; i++)
            {
                VkMemoryRequirements memReq = memReqs.get(i).memoryRequirements();

                VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReq.size())
                    .memoryTypeIndex(findMemoryType(
                        memReq.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        stack));

                LongBuffer pMemory = stack.mallocLong(1);
                int result = vkAllocateMemory(device, allocInfo, null, pMemory);
                if (result != VK_SUCCESS)
                {
                    throw new RuntimeException("vkAllocateMemory for video session failed: " + result);
                }

                sessionMemoryAllocations[i] = pMemory.get(0);

                bindInfos.get(i)
                    .sType(VK_STRUCTURE_TYPE_BIND_VIDEO_SESSION_MEMORY_INFO_KHR)
                    .memoryBindIndex(memReqs.get(i).memoryBindIndex())
                    .memory(pMemory.get(0))
                    .memoryOffset(0)
                    .memorySize(memReq.size());
            }

            int result = vkBindVideoSessionMemoryKHR(device, videoSession, bindInfos);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkBindVideoSessionMemoryKHR failed: " + result);
            }
        }
    }

    private void createSessionParameters()
    {
        // These structs must survive beyond the stack scope (session lifetime)
        spsFlags = StdVideoH264SpsFlags.calloc()
            .frame_mbs_only_flag(true)
            .direct_8x8_inference_flag(true)
            .vui_parameters_present_flag(true);

        // SPS VUI: match the on-device BGRA->NV12 shader, which writes
        // BT.601 limited-range luma/chroma values.
        spsVuiFlags = StdVideoH264SpsVuiFlags.calloc()
            .video_signal_type_present_flag(true)
            .video_full_range_flag(false)
            .color_description_present_flag(true)
            .timing_info_present_flag(true)
            .fixed_frame_rate_flag(true);

        spsVui = StdVideoH264SequenceParameterSetVui.calloc()
            .flags(spsVuiFlags)
            .video_format((byte) 5)          // 5 = Unspecified
            .colour_primaries((byte) 2)      // 2 = Unspecified
            .transfer_characteristics((byte) 2) // 2 = Unspecified
            .matrix_coefficients((byte) 6)   // 6 = BT.601
            .num_units_in_tick(1)
            .time_scale(fps * 2);            // H.264 convention: time_scale = 2·fps when tick=1

        sps = StdVideoH264SequenceParameterSet.calloc()
            .flags(spsFlags)
            .pSequenceParameterSetVui(spsVui)
            .profile_idc(STD_VIDEO_H264_PROFILE_IDC_HIGH)
            .level_idc(STD_VIDEO_H264_LEVEL_IDC_4_0)
            .chroma_format_idc(STD_VIDEO_H264_CHROMA_FORMAT_IDC_420)
            .seq_parameter_set_id((byte) 0)
            .bit_depth_luma_minus8((byte) 0)
            .bit_depth_chroma_minus8((byte) 0)
            // MaxFrameNum = 16; frame_num is allowed to wrap mid-GOP per spec.
            .log2_max_frame_num_minus4((byte) 0)
            // POC_TYPE_0 signals POC LSB explicitly per slice; some encoder
            // implementations don't handle POC_TYPE_2 for inter-prediction.
            .pic_order_cnt_type(STD_VIDEO_H264_POC_TYPE_0)
            .log2_max_pic_order_cnt_lsb_minus4((byte) 4)
            // IPPP only references the previous frame; declaring more confuses
            // the encoder about back-buffer tracking.
            .max_num_ref_frames((byte) 1)
            .pic_width_in_mbs_minus1((width + 15) / 16 - 1)
            .pic_height_in_map_units_minus1((height + 15) / 16 - 1);

        // Apply frame cropping if dimensions aren't multiples of 16
        int mbWidth = ((width + 15) / 16) * 16;
        int mbHeight = ((height + 15) / 16) * 16;
        if (mbWidth != width || mbHeight != height)
        {
            spsFlags.frame_cropping_flag(true);
            sps.frame_crop_right_offset((mbWidth - width) / 2);
            sps.frame_crop_bottom_offset((mbHeight - height) / 2);
        }

        // CABAC unlocks the hardware rate-control path on AMD; CAVLC falls
        // back to fixed QP. Gate on caps.stdSyntaxFlags.
        boolean cabacAvailable = (caps.getH264StdSyntaxFlags()
            & VK_VIDEO_ENCODE_H264_STD_ENTROPY_CODING_MODE_FLAG_SET_BIT_KHR) != 0;

        ppsFlags = StdVideoH264PpsFlags.calloc()
            .entropy_coding_mode_flag(cabacAvailable)
            .deblocking_filter_control_present_flag(true);

        pps = StdVideoH264PictureParameterSet.calloc()
            .flags(ppsFlags)
            .seq_parameter_set_id((byte) 0)
            .pic_parameter_set_id((byte) 0)
            .num_ref_idx_l0_default_active_minus1((byte) 0) // 1 L0 reference
            .num_ref_idx_l1_default_active_minus1((byte) 0)
            .pic_init_qp_minus26((byte) 0); // QP 26 default

        try (MemoryStack stack = stackPush())
        {
            StdVideoH264SequenceParameterSet.Buffer spsBuffer =
                StdVideoH264SequenceParameterSet.calloc(1, stack);
            spsBuffer.put(0, sps);

            StdVideoH264PictureParameterSet.Buffer ppsBuffer =
                StdVideoH264PictureParameterSet.calloc(1, stack);
            ppsBuffer.put(0, pps);

            VkVideoEncodeH264SessionParametersAddInfoKHR addInfo =
                VkVideoEncodeH264SessionParametersAddInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_SESSION_PARAMETERS_ADD_INFO_KHR)
                    .pStdSPSs(spsBuffer)
                    .pStdPPSs(ppsBuffer);

            VkVideoEncodeH264SessionParametersCreateInfoKHR h264ParamsInfo =
                VkVideoEncodeH264SessionParametersCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_SESSION_PARAMETERS_CREATE_INFO_KHR)
                    .maxStdSPSCount(1)
                    .maxStdPPSCount(1)
                    .pParametersAddInfo(addInfo);

            // Session parameters must declare the quality level; VUID-08318
            // requires this to match the level passed to the control command.
            VkVideoEncodeQualityLevelInfoKHR qlInfo = VkVideoEncodeQualityLevelInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_QUALITY_LEVEL_INFO_KHR)
                .pNext(h264ParamsInfo.address())
                .qualityLevel(2);

            VkVideoSessionParametersCreateInfoKHR paramsCreateInfo =
                VkVideoSessionParametersCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_VIDEO_SESSION_PARAMETERS_CREATE_INFO_KHR)
                    .pNext(qlInfo.address())
                    .videoSession(videoSession);

            LongBuffer pParams = stack.mallocLong(1);
            int result = vkCreateVideoSessionParametersKHR(
                vulkanDevice.getDevice(), paramsCreateInfo, null, pParams);
            if (result != VK_SUCCESS)
            {
                throw new RuntimeException("vkCreateVideoSessionParametersKHR failed: " + result);
            }
            sessionParameters = pParams.get(0);
        }
    }

    private int findMemoryType(int typeFilter, int properties, MemoryStack stack)
    {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(vulkanDevice.getPhysicalDevice(), memProps);

        for (int i = 0; i < memProps.memoryTypeCount(); i++)
        {
            if ((typeFilter & (1 << i)) != 0 &&
                (memProps.memoryTypes(i).propertyFlags() & properties) == properties)
            {
                return i;
            }
        }

        // Fallback: accept any matching type filter
        for (int i = 0; i < memProps.memoryTypeCount(); i++)
        {
            if ((typeFilter & (1 << i)) != 0)
            {
                return i;
            }
        }

        throw new RuntimeException("Failed to find suitable memory type");
    }

    public long getVideoSession() { return videoSession; }
    public long getSessionParameters() { return sessionParameters; }

    /**
     * Fetches the encoded SPS and PPS NAL bodies from the driver via
     * {@code vkGetEncodedVideoSessionParametersKHR}.
     *
     * The returned blob is the raw driver-produced concatenation: one or more
     * NAL unit bodies, without Annex-B start codes.
     */
    public byte[] fetchEncodedSpsPps(int stdSpsId, int stdPpsId, boolean writeSps, boolean writePps)
    {
        VkDevice device = vulkanDevice.getDevice();

        try (MemoryStack stack = stackPush())
        {
            VkVideoEncodeH264SessionParametersGetInfoKHR h264Get =
                VkVideoEncodeH264SessionParametersGetInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_H264_SESSION_PARAMETERS_GET_INFO_KHR)
                    .writeStdSPS(writeSps)
                    .writeStdPPS(writePps)
                    .stdSPSId(stdSpsId)
                    .stdPPSId(stdPpsId);

            VkVideoEncodeSessionParametersGetInfoKHR getInfo =
                VkVideoEncodeSessionParametersGetInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_VIDEO_ENCODE_SESSION_PARAMETERS_GET_INFO_KHR)
                    .pNext(h264Get.address())
                    .videoSessionParameters(sessionParameters);

            PointerBuffer pDataSize = stack.mallocPointer(1);
            pDataSize.put(0, 0L);

            int r = vkGetEncodedVideoSessionParametersKHR(device, getInfo, null, pDataSize, null);
            if (r != VK_SUCCESS)
            {
                log.warn("vkGetEncodedVideoSessionParametersKHR size query failed: {}", r);
                return null;
            }
            int size = (int) pDataSize.get(0);
            if (size == 0) return null;

            ByteBuffer data = MemoryUtil.memAlloc(size);
            try
            {
                r = vkGetEncodedVideoSessionParametersKHR(device, getInfo, null, pDataSize, data);
                if (r != VK_SUCCESS)
                {
                    log.warn("vkGetEncodedVideoSessionParametersKHR fetch failed: {}", r);
                    return null;
                }
                byte[] out = new byte[(int) pDataSize.get(0)];
                data.get(out);
                return out;
            }
            finally
            {
                MemoryUtil.memFree(data);
            }
        }
    }

    @Override
    public void close()
    {
        if (closed)
        {
            return;
        }
        closed = true;

        VkDevice device = vulkanDevice.getDevice();

        if (sessionParameters != VK_NULL_HANDLE)
        {
            vkDestroyVideoSessionParametersKHR(device, sessionParameters, null);
        }
        if (videoSession != VK_NULL_HANDLE)
        {
            vkDestroyVideoSessionKHR(device, videoSession, null);
        }

        // Free session memory allocations
        if (sessionMemoryAllocations != null)
        {
            for (long mem : sessionMemoryAllocations)
            {
                if (mem != VK_NULL_HANDLE)
                {
                    vkFreeMemory(device, mem, null);
                }
            }
            sessionMemoryAllocations = null;
        }

        // Free heap-allocated std structs
        if (pps != null) pps.free();
        if (ppsFlags != null) ppsFlags.free();
        if (sps != null) sps.free();
        if (spsFlags != null) spsFlags.free();
        if (spsVui != null) spsVui.free();
        if (spsVuiFlags != null) spsVuiFlags.free();
    }
}
