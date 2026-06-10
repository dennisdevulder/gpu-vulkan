/*
 * Copyright (c) 2026, Dennis de Vulder
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
package net.runelite.client.plugins.gpuvulkan;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRVideoEncodeH264;
import org.lwjgl.vulkan.KHRVideoEncodeQueue;
import org.lwjgl.vulkan.KHRVideoQueue;
import org.lwjgl.vulkan.VkBindVideoSessionMemoryInfoKHR;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVideoFormatInfoKHR;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueryPoolVideoEncodeFeedbackCreateInfoKHR;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.lwjgl.vulkan.VkVideoBeginCodingInfoKHR;
import org.lwjgl.vulkan.VkVideoCapabilitiesKHR;
import org.lwjgl.vulkan.VkVideoCodingControlInfoKHR;
import org.lwjgl.vulkan.VkVideoEncodeCapabilitiesKHR;
import org.lwjgl.vulkan.VkVideoEncodeH264CapabilitiesKHR;
import org.lwjgl.vulkan.VkVideoEncodeH264DpbSlotInfoKHR;
import org.lwjgl.vulkan.VkVideoEncodeH264NaluSliceInfoKHR;
import org.lwjgl.vulkan.VkVideoEncodeH264PictureInfoKHR;
import org.lwjgl.vulkan.VkVideoEncodeH264ProfileInfoKHR;
import org.lwjgl.vulkan.VkVideoEncodeH264SessionParametersAddInfoKHR;
import org.lwjgl.vulkan.VkVideoEncodeH264SessionParametersCreateInfoKHR;
import org.lwjgl.vulkan.VkVideoEncodeH264SessionParametersGetInfoKHR;
import org.lwjgl.vulkan.VkVideoEncodeInfoKHR;
import org.lwjgl.vulkan.VkVideoEncodeRateControlInfoKHR;
import org.lwjgl.vulkan.VkVideoEncodeSessionParametersGetInfoKHR;
import org.lwjgl.vulkan.VkVideoEndCodingInfoKHR;
import org.lwjgl.vulkan.VkVideoFormatPropertiesKHR;
import org.lwjgl.vulkan.VkVideoPictureResourceInfoKHR;
import org.lwjgl.vulkan.VkVideoProfileInfoKHR;
import org.lwjgl.vulkan.VkVideoProfileListInfoKHR;
import org.lwjgl.vulkan.VkVideoReferenceSlotInfoKHR;
import org.lwjgl.vulkan.VkVideoSessionCreateInfoKHR;
import org.lwjgl.vulkan.VkVideoSessionMemoryRequirementsKHR;
import org.lwjgl.vulkan.video.StdVideoEncodeH264PictureInfo;
import org.lwjgl.vulkan.video.StdVideoEncodeH264ReferenceInfo;
import org.lwjgl.vulkan.video.StdVideoEncodeH264ReferenceListsInfo;
import org.lwjgl.vulkan.video.StdVideoEncodeH264SliceHeader;
import org.lwjgl.vulkan.video.StdVideoH264PictureParameterSet;
import org.lwjgl.vulkan.video.StdVideoH264SequenceParameterSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.*;
import static org.lwjgl.vulkan.KHRVideoQueue.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.video.STDVulkanVideoCodecH264.*;

/**
 * One H.264 encode session on the device's video encode queue: NV12 in,
 * Annex-B access units out. Every frame is encoded as an IDR picture with a
 * constant QP (rate control disabled), which needs no reference management
 * and no reordering — the simplest stream that proves the encode path
 * end-to-end. All submissions run on the caller's thread; the encode queue
 * is touched by no one else.
 */
@Slf4j
final class H264EncodeSession implements AutoCloseable
{
	private static final int BITSTREAM_BUFFER_SIZE = 4 << 20;

	private final VulkanDevice device;
	private final int width;
	private final int height;
	private final int paddedWidth;
	private final int paddedHeight;
	private final int slots;
	private final int qp;
	private final boolean rateControlDisabled;

	private long videoSession;
	private long sessionParameters;
	private long[] sessionMemory = new long[0];
	private long commandPool;
	private long queryPool;
	private final VkCommandBuffer[] commands;
	private final long[] fences;
	private final long[] srcImages;
	private final long[] srcImageMemory;
	private final long[] srcImageViews;
	private long dpbImage;
	private long dpbImageMemory;
	private long dpbImageView;
	private final long[] bitstreamBuffers;
	private final long[] bitstreamMemory;
	private final ByteBuffer[] bitstreamMapped;
	private byte[] parameterHeader;
	private boolean resetRecorded;
	private long frameIndex;

	H264EncodeSession(VulkanDevice device, int width, int height, int slots, int qp)
	{
		this.device = device;
		this.width = width;
		this.height = height;
		this.paddedWidth = (width + 15) & ~15;
		this.paddedHeight = (height + 15) & ~15;
		this.slots = slots;
		this.commands = new VkCommandBuffer[slots];
		this.fences = new long[slots];
		this.srcImages = new long[slots];
		this.srcImageMemory = new long[slots];
		this.srcImageViews = new long[slots];
		this.bitstreamBuffers = new long[slots];
		this.bitstreamMemory = new long[slots];
		this.bitstreamMapped = new ByteBuffer[slots];

		try (MemoryStack stack = stackPush())
		{
			VkVideoProfileInfoKHR profile = h264Profile(stack);

			VkVideoCapabilitiesKHR caps = VkVideoCapabilitiesKHR.calloc(stack).sType$Default();
			VkVideoEncodeCapabilitiesKHR encodeCaps = VkVideoEncodeCapabilitiesKHR.calloc(stack).sType$Default();
			VkVideoEncodeH264CapabilitiesKHR h264Caps = VkVideoEncodeH264CapabilitiesKHR.calloc(stack).sType$Default();
			caps.pNext(encodeCaps.address());
			encodeCaps.pNext(h264Caps.address());
			Vk.check("vkGetPhysicalDeviceVideoCapabilitiesKHR",
				vkGetPhysicalDeviceVideoCapabilitiesKHR(device.physicalDevice(), profile, caps));

			if (paddedWidth > caps.maxCodedExtent().width() || paddedHeight > caps.maxCodedExtent().height())
			{
				throw new IllegalStateException("Encode max coded extent "
					+ caps.maxCodedExtent().width() + "x" + caps.maxCodedExtent().height()
					+ " below frame size " + paddedWidth + "x" + paddedHeight);
			}
			this.rateControlDisabled =
				(encodeCaps.rateControlModes() & VK_VIDEO_ENCODE_RATE_CONTROL_MODE_DISABLED_BIT_KHR) != 0;
			this.qp = rateControlDisabled
				? Math.max(h264Caps.minQp(), Math.min(h264Caps.maxQp(), qp))
				: 0;

			checkNv12Supported(stack, profile);
			createSession(stack, profile, caps.stdHeaderVersion());
			bindSessionMemory(stack);
			createSessionParameters(stack, profile, h264Caps.maxLevelIdc());
			fetchParameterHeader(stack);
			createImages(stack, profile);
			createBitstreamBuffers(stack, profile);
			createCommandResources(stack, profile);
			log.info("H264 encode session ready: {}x{} (coded {}x{}), QP {} (rate control {}), {} slots",
				width, height, paddedWidth, paddedHeight, this.qp,
				rateControlDisabled ? "disabled/constant-QP" : "driver default", slots);
		}
		catch (RuntimeException e)
		{
			close();
			throw e;
		}
	}

	/** SPS+PPS Annex-B bytes — write once at the head of the stream. */
	byte[] parameterHeader()
	{
		return parameterHeader;
	}

	long srcImage(int slot)
	{
		return srcImages[slot];
	}

	int paddedWidth()
	{
		return paddedWidth;
	}

	int paddedHeight()
	{
		return paddedHeight;
	}

	/**
	 * Encodes the NV12 content of {@code srcImage(slot)} (expected in
	 * TRANSFER_DST_OPTIMAL, written by the graphics queue — the caller must
	 * have host-waited the frame timeline first) and returns the encoded
	 * access unit. Blocks until the encode queue finishes the frame.
	 */
	byte[] encode(int slot)
	{
		try (MemoryStack stack = stackPush())
		{
			VkCommandBuffer cmd = commands[slot];
			Vk.check("vkResetFences", vkResetFences(device.handle(), fences[slot]));
			Vk.check("vkResetCommandBuffer", vkResetCommandBuffer(cmd, 0));
			VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
				.sType$Default()
				.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
			Vk.check("vkBeginCommandBuffer", vkBeginCommandBuffer(cmd, begin));

			recordPreEncodeBarriers(stack, cmd, slot);

			VkVideoPictureResourceInfoKHR dpbResource = VkVideoPictureResourceInfoKHR.calloc(stack)
				.sType$Default()
				.codedExtent(e -> e.set(width, height))
				.imageViewBinding(dpbImageView);

			StdVideoEncodeH264ReferenceInfo stdRef = StdVideoEncodeH264ReferenceInfo.calloc(stack)
				.primary_pic_type(STD_VIDEO_H264_PICTURE_TYPE_IDR);
			VkVideoEncodeH264DpbSlotInfoKHR dpbSlotInfo = VkVideoEncodeH264DpbSlotInfoKHR.calloc(stack)
				.sType$Default()
				.pStdReferenceInfo(stdRef);

			// Begin-info must list every DPB slot the command buffer touches;
			// slotIndex -1 marks it inactive until the encode activates it.
			VkVideoReferenceSlotInfoKHR.Buffer beginSlots = VkVideoReferenceSlotInfoKHR.calloc(1, stack);
			beginSlots.get(0)
				.sType$Default()
				.pNext(dpbSlotInfo.address())
				.slotIndex(-1)
				.pPictureResource(dpbResource);

			VkVideoBeginCodingInfoKHR beginCoding = VkVideoBeginCodingInfoKHR.calloc(stack)
				.sType$Default()
				.videoSession(videoSession)
				.videoSessionParameters(sessionParameters)
				.pReferenceSlots(beginSlots);

			VkVideoEncodeRateControlInfoKHR rateControl = null;
			if (rateControlDisabled)
			{
				rateControl = VkVideoEncodeRateControlInfoKHR.calloc(stack)
					.sType$Default()
					.rateControlMode(VK_VIDEO_ENCODE_RATE_CONTROL_MODE_DISABLED_BIT_KHR);
				if (resetRecorded)
				{
					beginCoding.pNext(rateControl.address());
				}
			}

			vkCmdBeginVideoCodingKHR(cmd, beginCoding);

			if (!resetRecorded)
			{
				VkVideoCodingControlInfoKHR reset = VkVideoCodingControlInfoKHR.calloc(stack)
					.sType$Default()
					.flags(VK_VIDEO_CODING_CONTROL_RESET_BIT_KHR);
				vkCmdControlVideoCodingKHR(cmd, reset);
				if (rateControl != null)
				{
					VkVideoCodingControlInfoKHR setRate = VkVideoCodingControlInfoKHR.calloc(stack)
						.sType$Default()
						.flags(VK_VIDEO_CODING_CONTROL_ENCODE_RATE_CONTROL_BIT_KHR)
						.pNext(rateControl.address());
					vkCmdControlVideoCodingKHR(cmd, setRate);
				}
				resetRecorded = true;
			}

			recordEncode(stack, cmd, slot, dpbResource, dpbSlotInfo);

			vkCmdEndVideoCodingKHR(cmd, VkVideoEndCodingInfoKHR.calloc(stack).sType$Default());
			Vk.check("vkEndCommandBuffer", vkEndCommandBuffer(cmd));

			VkCommandBufferSubmitInfo.Buffer cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack);
			cmdInfo.get(0)
				.sType$Default()
				.commandBuffer(cmd);
			VkSubmitInfo2.Buffer submit = VkSubmitInfo2.calloc(1, stack);
			submit.get(0)
				.sType$Default()
				.pCommandBufferInfos(cmdInfo);
			Vk.check("vkQueueSubmit2 (encode)",
				vkQueueSubmit2(device.videoEncodeQueue(), submit, fences[slot]));
			Vk.check("vkWaitForFences (encode)",
				vkWaitForFences(device.handle(), fences[slot], true, 5_000_000_000L));

			return readFeedback(stack, slot);
		}
	}

	private void recordPreEncodeBarriers(MemoryStack stack, VkCommandBuffer cmd, int slot)
	{
		boolean firstUse = !resetRecorded;
		VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(firstUse ? 2 : 1, stack);
		// The graphics queue finished writing before the caller's host wait,
		// so no execution dependency is needed — just the layout transition.
		barriers.get(0)
			.sType$Default()
			.srcStageMask(VK_PIPELINE_STAGE_2_NONE)
			.srcAccessMask(0)
			.dstStageMask(VK_PIPELINE_STAGE_2_VIDEO_ENCODE_BIT_KHR)
			.dstAccessMask(VK_ACCESS_2_VIDEO_ENCODE_READ_BIT_KHR)
			.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
			.newLayout(VK_IMAGE_LAYOUT_VIDEO_ENCODE_SRC_KHR)
			.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
			.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
			.image(srcImages[slot])
			.subresourceRange(r -> r
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(1));
		if (firstUse)
		{
			barriers.get(1)
				.sType$Default()
				.srcStageMask(VK_PIPELINE_STAGE_2_NONE)
				.srcAccessMask(0)
				.dstStageMask(VK_PIPELINE_STAGE_2_VIDEO_ENCODE_BIT_KHR)
				.dstAccessMask(VK_ACCESS_2_VIDEO_ENCODE_READ_BIT_KHR | VK_ACCESS_2_VIDEO_ENCODE_WRITE_BIT_KHR)
				.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
				.newLayout(VK_IMAGE_LAYOUT_VIDEO_ENCODE_DPB_KHR)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image(dpbImage)
				.subresourceRange(r -> r
					.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
					.baseMipLevel(0).levelCount(1)
					.baseArrayLayer(0).layerCount(1));
		}
		VkDependencyInfo dep = VkDependencyInfo.calloc(stack)
			.sType$Default()
			.pImageMemoryBarriers(barriers);
		vkCmdPipelineBarrier2(cmd, dep);
	}

	private void recordEncode(MemoryStack stack, VkCommandBuffer cmd, int slot,
		VkVideoPictureResourceInfoKHR dpbResource, VkVideoEncodeH264DpbSlotInfoKHR dpbSlotInfo)
	{
		StdVideoEncodeH264SliceHeader stdSlice = StdVideoEncodeH264SliceHeader.calloc(stack)
			.slice_type(STD_VIDEO_H264_SLICE_TYPE_I);

		VkVideoEncodeH264NaluSliceInfoKHR.Buffer slice = VkVideoEncodeH264NaluSliceInfoKHR.calloc(1, stack);
		slice.get(0)
			.sType$Default()
			.constantQp(rateControlDisabled ? qp : 0)
			.pStdSliceHeader(stdSlice);

		// IDR-only: every list entry is "no reference".
		StdVideoEncodeH264ReferenceListsInfo refLists = StdVideoEncodeH264ReferenceListsInfo.calloc(stack);
		for (int i = 0; i < STD_VIDEO_H264_MAX_NUM_LIST_REF; i++)
		{
			refLists.RefPicList0().put(i, (byte) 0xFF);
			refLists.RefPicList1().put(i, (byte) 0xFF);
		}

		StdVideoEncodeH264PictureInfo stdPicture = StdVideoEncodeH264PictureInfo.calloc(stack)
			.flags(f -> f
				.IdrPicFlag(true)
				.is_reference(true))
			.primary_pic_type(STD_VIDEO_H264_PICTURE_TYPE_IDR)
			.idr_pic_id((short) (frameIndex & 1))
			.pRefLists(refLists);

		VkVideoEncodeH264PictureInfoKHR h264Picture = VkVideoEncodeH264PictureInfoKHR.calloc(stack)
			.sType$Default()
			.pNaluSliceEntries(slice)
			.pStdPictureInfo(stdPicture);

		VkVideoReferenceSlotInfoKHR setupSlot = VkVideoReferenceSlotInfoKHR.calloc(stack)
			.sType$Default()
			.pNext(dpbSlotInfo.address())
			.slotIndex(0)
			.pPictureResource(dpbResource);

		VkVideoEncodeInfoKHR encodeInfo = VkVideoEncodeInfoKHR.calloc(stack)
			.sType$Default()
			.pNext(h264Picture.address())
			.dstBuffer(bitstreamBuffers[slot])
			.dstBufferOffset(0)
			.dstBufferRange(BITSTREAM_BUFFER_SIZE)
			.pSetupReferenceSlot(setupSlot);
		encodeInfo.srcPictureResource()
			.sType$Default()
			.codedExtent(e -> e.set(width, height))
			.imageViewBinding(srcImageViews[slot]);

		vkCmdResetQueryPool(cmd, queryPool, slot, 1);
		vkCmdBeginQuery(cmd, queryPool, slot, 0);
		vkCmdEncodeVideoKHR(cmd, encodeInfo);
		vkCmdEndQuery(cmd, queryPool, slot);
		frameIndex++;
	}

	private byte[] readFeedback(MemoryStack stack, int slot)
	{
		// offset, bytesWritten, then the status value appended by WITH_STATUS.
		IntBuffer results = stack.mallocInt(3);
		Vk.check("vkGetQueryPoolResults (encode feedback)",
			vkGetQueryPoolResults(device.handle(), queryPool, slot, 1,
				results, 12, VK_QUERY_RESULT_WAIT_BIT | VK_QUERY_RESULT_WITH_STATUS_BIT_KHR));
		int status = results.get(2);
		if (status <= 0)
		{
			throw new IllegalStateException("Video encode feedback status " + status);
		}
		int offset = results.get(0);
		int size = results.get(1);
		byte[] out = new byte[size];
		ByteBuffer mapped = bitstreamMapped[slot].duplicate();
		mapped.position(offset).limit(offset + size);
		mapped.get(out);
		return out;
	}

	// ---- setup ------------------------------------------------------------

	private VkVideoProfileInfoKHR h264Profile(MemoryStack stack)
	{
		VkVideoEncodeH264ProfileInfoKHR h264 = VkVideoEncodeH264ProfileInfoKHR.calloc(stack)
			.sType$Default()
			.stdProfileIdc(STD_VIDEO_H264_PROFILE_IDC_MAIN);
		return VkVideoProfileInfoKHR.calloc(stack)
			.sType$Default()
			.pNext(h264.address())
			.videoCodecOperation(KHRVideoEncodeH264.VK_VIDEO_CODEC_OPERATION_ENCODE_H264_BIT_KHR)
			.chromaSubsampling(VK_VIDEO_CHROMA_SUBSAMPLING_420_BIT_KHR)
			.lumaBitDepth(VK_VIDEO_COMPONENT_BIT_DEPTH_8_BIT_KHR)
			.chromaBitDepth(VK_VIDEO_COMPONENT_BIT_DEPTH_8_BIT_KHR);
	}

	private VkVideoProfileListInfoKHR profileList(MemoryStack stack, VkVideoProfileInfoKHR profile)
	{
		return VkVideoProfileListInfoKHR.calloc(stack)
			.sType$Default()
			.pProfiles(VkVideoProfileInfoKHR.create(profile.address(), 1));
	}

	private void checkNv12Supported(MemoryStack stack, VkVideoProfileInfoKHR profile)
	{
		for (int usage : new int[]{
			VK_IMAGE_USAGE_VIDEO_ENCODE_SRC_BIT_KHR,
			VK_IMAGE_USAGE_VIDEO_ENCODE_DPB_BIT_KHR})
		{
			VkPhysicalDeviceVideoFormatInfoKHR info = VkPhysicalDeviceVideoFormatInfoKHR.calloc(stack)
				.sType$Default()
				.pNext(profileList(stack, profile).address())
				.imageUsage(usage);
			IntBuffer count = stack.mallocInt(1);
			Vk.check("vkGetPhysicalDeviceVideoFormatPropertiesKHR (count)",
				vkGetPhysicalDeviceVideoFormatPropertiesKHR(device.physicalDevice(), info, count, null));
			VkVideoFormatPropertiesKHR.Buffer formats = VkVideoFormatPropertiesKHR.calloc(count.get(0), stack);
			for (int i = 0; i < formats.capacity(); i++)
			{
				formats.get(i).sType$Default();
			}
			Vk.check("vkGetPhysicalDeviceVideoFormatPropertiesKHR",
				vkGetPhysicalDeviceVideoFormatPropertiesKHR(device.physicalDevice(), info, count, formats));
			boolean nv12 = false;
			for (int i = 0; i < count.get(0); i++)
			{
				nv12 |= formats.get(i).format() == VK_FORMAT_G8_B8R8_2PLANE_420_UNORM;
			}
			if (!nv12)
			{
				throw new IllegalStateException("Device does not support NV12 for video encode usage 0x"
					+ Integer.toHexString(usage));
			}
		}
	}

	private void createSession(MemoryStack stack, VkVideoProfileInfoKHR profile, VkExtensionProperties stdHeader)
	{
		VkVideoSessionCreateInfoKHR info = VkVideoSessionCreateInfoKHR.calloc(stack)
			.sType$Default()
			.queueFamilyIndex(device.videoEncodeQueueFamily())
			.pVideoProfile(profile)
			.pictureFormat(VK_FORMAT_G8_B8R8_2PLANE_420_UNORM)
			.maxCodedExtent(e -> e.set(paddedWidth, paddedHeight))
			.referencePictureFormat(VK_FORMAT_G8_B8R8_2PLANE_420_UNORM)
			.maxDpbSlots(1)
			.maxActiveReferencePictures(0)
			.pStdHeaderVersion(stdHeader);
		LongBuffer pSession = stack.mallocLong(1);
		Vk.check("vkCreateVideoSessionKHR",
			vkCreateVideoSessionKHR(device.handle(), info, null, pSession));
		videoSession = pSession.get(0);
	}

	private void bindSessionMemory(MemoryStack stack)
	{
		IntBuffer count = stack.mallocInt(1);
		Vk.check("vkGetVideoSessionMemoryRequirementsKHR (count)",
			vkGetVideoSessionMemoryRequirementsKHR(device.handle(), videoSession, count, null));
		int n = count.get(0);
		VkVideoSessionMemoryRequirementsKHR.Buffer reqs = VkVideoSessionMemoryRequirementsKHR.calloc(n, stack);
		for (int i = 0; i < n; i++)
		{
			reqs.get(i).sType$Default();
		}
		Vk.check("vkGetVideoSessionMemoryRequirementsKHR",
			vkGetVideoSessionMemoryRequirementsKHR(device.handle(), videoSession, count, reqs));

		sessionMemory = new long[n];
		VkBindVideoSessionMemoryInfoKHR.Buffer binds = VkBindVideoSessionMemoryInfoKHR.calloc(n, stack);
		for (int i = 0; i < n; i++)
		{
			VkMemoryRequirements memReq = reqs.get(i).memoryRequirements();
			sessionMemory[i] = allocateMemory(stack, memReq.size(),
				memReq.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
			binds.get(i)
				.sType$Default()
				.memoryBindIndex(reqs.get(i).memoryBindIndex())
				.memory(sessionMemory[i])
				.memoryOffset(0)
				.memorySize(memReq.size());
		}
		Vk.check("vkBindVideoSessionMemoryKHR",
			vkBindVideoSessionMemoryKHR(device.handle(), videoSession, binds));
	}

	private void createSessionParameters(MemoryStack stack, VkVideoProfileInfoKHR profile, int levelIdc)
	{
		int cropRight = (paddedWidth - width) / 2;   // crop units are 2px for 4:2:0
		int cropBottom = (paddedHeight - height) / 2;
		StdVideoH264SequenceParameterSet sps = StdVideoH264SequenceParameterSet.calloc(stack)
			.profile_idc(STD_VIDEO_H264_PROFILE_IDC_MAIN)
			.level_idc(levelIdc)
			.chroma_format_idc(STD_VIDEO_H264_CHROMA_FORMAT_IDC_420)
			.log2_max_frame_num_minus4((byte) 4)
			.pic_order_cnt_type(STD_VIDEO_H264_POC_TYPE_2)
			.max_num_ref_frames((byte) 1)
			.pic_width_in_mbs_minus1(paddedWidth / 16 - 1)
			.pic_height_in_map_units_minus1(paddedHeight / 16 - 1)
			.frame_crop_right_offset(cropRight)
			.frame_crop_bottom_offset(cropBottom);
		sps.flags()
			.frame_mbs_only_flag(true)
			.direct_8x8_inference_flag(true)
			.frame_cropping_flag(cropRight != 0 || cropBottom != 0);

		StdVideoH264PictureParameterSet pps = StdVideoH264PictureParameterSet.calloc(stack)
			.pic_init_qp_minus26((byte) (rateControlDisabled ? qp - 26 : 0));
		pps.flags()
			.entropy_coding_mode_flag(false)
			.deblocking_filter_control_present_flag(true);

		VkVideoEncodeH264SessionParametersAddInfoKHR add = VkVideoEncodeH264SessionParametersAddInfoKHR.calloc(stack)
			.sType$Default()
			.pStdSPSs(StdVideoH264SequenceParameterSet.create(sps.address(), 1))
			.pStdPPSs(StdVideoH264PictureParameterSet.create(pps.address(), 1));
		VkVideoEncodeH264SessionParametersCreateInfoKHR h264Params =
			VkVideoEncodeH264SessionParametersCreateInfoKHR.calloc(stack)
				.sType$Default()
				.maxStdSPSCount(1)
				.maxStdPPSCount(1)
				.pParametersAddInfo(add);
		org.lwjgl.vulkan.VkVideoSessionParametersCreateInfoKHR info =
			org.lwjgl.vulkan.VkVideoSessionParametersCreateInfoKHR.calloc(stack)
				.sType$Default()
				.pNext(h264Params.address())
				.videoSession(videoSession);
		LongBuffer pParams = stack.mallocLong(1);
		Vk.check("vkCreateVideoSessionParametersKHR",
			vkCreateVideoSessionParametersKHR(device.handle(), info, null, pParams));
		sessionParameters = pParams.get(0);
	}

	private void fetchParameterHeader(MemoryStack stack)
	{
		VkVideoEncodeH264SessionParametersGetInfoKHR h264Get =
			VkVideoEncodeH264SessionParametersGetInfoKHR.calloc(stack)
				.sType$Default()
				.writeStdSPS(true)
				.writeStdPPS(true);
		VkVideoEncodeSessionParametersGetInfoKHR get = VkVideoEncodeSessionParametersGetInfoKHR.calloc(stack)
			.sType$Default()
			.pNext(h264Get.address())
			.videoSessionParameters(sessionParameters);

		PointerBuffer pSize = stack.mallocPointer(1);
		Vk.check("vkGetEncodedVideoSessionParametersKHR (size)",
			vkGetEncodedVideoSessionParametersKHR(device.handle(), get, null, pSize, null));
		ByteBuffer data = stack.malloc((int) pSize.get(0));
		Vk.check("vkGetEncodedVideoSessionParametersKHR",
			vkGetEncodedVideoSessionParametersKHR(device.handle(), get, null, pSize, data));
		parameterHeader = new byte[(int) pSize.get(0)];
		data.limit(parameterHeader.length);
		data.get(parameterHeader);
	}

	private void createImages(MemoryStack stack, VkVideoProfileInfoKHR profile)
	{
		IntBuffer families = stack.ints(device.graphicsQueueFamily(), device.videoEncodeQueueFamily());
		for (int i = 0; i < slots; i++)
		{
			long[] created = createImage(stack, profile,
				VK_IMAGE_USAGE_VIDEO_ENCODE_SRC_BIT_KHR | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
				families);
			srcImages[i] = created[0];
			srcImageMemory[i] = created[1];
			srcImageViews[i] = createView(stack, srcImages[i]);
		}
		long[] dpb = createImage(stack, profile, VK_IMAGE_USAGE_VIDEO_ENCODE_DPB_BIT_KHR, null);
		dpbImage = dpb[0];
		dpbImageMemory = dpb[1];
		dpbImageView = createView(stack, dpbImage);
	}

	private long[] createImage(MemoryStack stack, VkVideoProfileInfoKHR profile, int usage, IntBuffer shareFamilies)
	{
		VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
			.sType$Default()
			.pNext(profileList(stack, profile).address())
			.imageType(VK_IMAGE_TYPE_2D)
			.format(VK_FORMAT_G8_B8R8_2PLANE_420_UNORM)
			.extent(e -> e.set(paddedWidth, paddedHeight, 1))
			.mipLevels(1)
			.arrayLayers(1)
			.samples(VK_SAMPLE_COUNT_1_BIT)
			.tiling(VK_IMAGE_TILING_OPTIMAL)
			.usage(usage)
			.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
		if (shareFamilies != null && shareFamilies.get(0) != shareFamilies.get(1))
		{
			info.sharingMode(VK_SHARING_MODE_CONCURRENT)
				.pQueueFamilyIndices(shareFamilies);
		}
		LongBuffer pImage = stack.mallocLong(1);
		Vk.check("vkCreateImage (encode)", vkCreateImage(device.handle(), info, null, pImage));
		long image = pImage.get(0);

		VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
		vkGetImageMemoryRequirements(device.handle(), image, req);
		long memory = allocateMemory(stack, req.size(), req.memoryTypeBits(),
			VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
		Vk.check("vkBindImageMemory (encode)", vkBindImageMemory(device.handle(), image, memory, 0));
		return new long[]{image, memory};
	}

	private long createView(MemoryStack stack, long image)
	{
		VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
			.sType$Default()
			.image(image)
			.viewType(VK_IMAGE_VIEW_TYPE_2D)
			.format(VK_FORMAT_G8_B8R8_2PLANE_420_UNORM)
			.subresourceRange(r -> r
				.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(1));
		LongBuffer pView = stack.mallocLong(1);
		Vk.check("vkCreateImageView (encode)", vkCreateImageView(device.handle(), info, null, pView));
		return pView.get(0);
	}

	private void createBitstreamBuffers(MemoryStack stack, VkVideoProfileInfoKHR profile)
	{
		for (int i = 0; i < slots; i++)
		{
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
				.sType$Default()
				.pNext(profileList(stack, profile).address())
				.size(BITSTREAM_BUFFER_SIZE)
				.usage(VK_BUFFER_USAGE_VIDEO_ENCODE_DST_BIT_KHR)
				.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			LongBuffer pBuffer = stack.mallocLong(1);
			Vk.check("vkCreateBuffer (bitstream)", vkCreateBuffer(device.handle(), info, null, pBuffer));
			bitstreamBuffers[i] = pBuffer.get(0);

			VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
			vkGetBufferMemoryRequirements(device.handle(), bitstreamBuffers[i], req);
			bitstreamMemory[i] = allocateMemory(stack, req.size(), req.memoryTypeBits(),
				VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
				VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
			Vk.check("vkBindBufferMemory (bitstream)",
				vkBindBufferMemory(device.handle(), bitstreamBuffers[i], bitstreamMemory[i], 0));

			PointerBuffer pMapped = stack.mallocPointer(1);
			Vk.check("vkMapMemory (bitstream)",
				vkMapMemory(device.handle(), bitstreamMemory[i], 0, BITSTREAM_BUFFER_SIZE, 0, pMapped));
			bitstreamMapped[i] = org.lwjgl.system.MemoryUtil.memByteBuffer(pMapped.get(0), BITSTREAM_BUFFER_SIZE);
		}
	}

	private void createCommandResources(MemoryStack stack, VkVideoProfileInfoKHR profile)
	{
		VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
			.sType$Default()
			.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
			.queueFamilyIndex(device.videoEncodeQueueFamily());
		LongBuffer pPool = stack.mallocLong(1);
		Vk.check("vkCreateCommandPool (encode)",
			vkCreateCommandPool(device.handle(), poolInfo, null, pPool));
		commandPool = pPool.get(0);

		VkCommandBufferAllocateInfo alloc = VkCommandBufferAllocateInfo.calloc(stack)
			.sType$Default()
			.commandPool(commandPool)
			.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
			.commandBufferCount(slots);
		PointerBuffer pCmds = stack.mallocPointer(slots);
		Vk.check("vkAllocateCommandBuffers (encode)",
			vkAllocateCommandBuffers(device.handle(), alloc, pCmds));
		VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
		for (int i = 0; i < slots; i++)
		{
			commands[i] = new VkCommandBuffer(pCmds.get(i), device.handle());
			LongBuffer pFence = stack.mallocLong(1);
			Vk.check("vkCreateFence (encode)", vkCreateFence(device.handle(), fenceInfo, null, pFence));
			fences[i] = pFence.get(0);
		}

		VkQueryPoolVideoEncodeFeedbackCreateInfoKHR feedback =
			VkQueryPoolVideoEncodeFeedbackCreateInfoKHR.calloc(stack)
				.sType$Default()
				.pNext(profile.address())
				.encodeFeedbackFlags(VK_VIDEO_ENCODE_FEEDBACK_BITSTREAM_BUFFER_OFFSET_BIT_KHR
					| VK_VIDEO_ENCODE_FEEDBACK_BITSTREAM_BYTES_WRITTEN_BIT_KHR);
		VkQueryPoolCreateInfo queryInfo = VkQueryPoolCreateInfo.calloc(stack)
			.sType$Default()
			.pNext(feedback.address())
			.queryType(VK_QUERY_TYPE_VIDEO_ENCODE_FEEDBACK_KHR)
			.queryCount(slots);
		LongBuffer pQuery = stack.mallocLong(1);
		Vk.check("vkCreateQueryPool (encode feedback)",
			vkCreateQueryPool(device.handle(), queryInfo, null, pQuery));
		queryPool = pQuery.get(0);
	}

	private long allocateMemory(MemoryStack stack, long size, int typeBits, int required, int preferred)
	{
		VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
		vkGetPhysicalDeviceMemoryProperties(device.physicalDevice(), props);
		int type = findMemoryType(props, typeBits, required | preferred);
		if (type < 0)
		{
			type = findMemoryType(props, typeBits, required);
		}
		if (type < 0)
		{
			throw new IllegalStateException("No memory type for video encode allocation");
		}
		VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
			.sType$Default()
			.allocationSize(size)
			.memoryTypeIndex(type);
		LongBuffer pMemory = stack.mallocLong(1);
		Vk.check("vkAllocateMemory (encode)", vkAllocateMemory(device.handle(), alloc, null, pMemory));
		return pMemory.get(0);
	}

	private static int findMemoryType(VkPhysicalDeviceMemoryProperties props, int typeBits, int flags)
	{
		for (int i = 0; i < props.memoryTypeCount(); i++)
		{
			if ((typeBits & (1 << i)) != 0
				&& (props.memoryTypes(i).propertyFlags() & flags) == flags)
			{
				return i;
			}
		}
		return -1;
	}

	@Override
	public void close()
	{
		if (device.videoEncodeQueue() != null)
		{
			vkQueueWaitIdle(device.videoEncodeQueue());
		}
		for (int i = 0; i < slots; i++)
		{
			if (fences[i] != VK_NULL_HANDLE) vkDestroyFence(device.handle(), fences[i], null);
			if (bitstreamMemory[i] != VK_NULL_HANDLE)
			{
				vkUnmapMemory(device.handle(), bitstreamMemory[i]);
				vkDestroyBuffer(device.handle(), bitstreamBuffers[i], null);
				vkFreeMemory(device.handle(), bitstreamMemory[i], null);
			}
			if (srcImageViews[i] != VK_NULL_HANDLE) vkDestroyImageView(device.handle(), srcImageViews[i], null);
			if (srcImages[i] != VK_NULL_HANDLE) vkDestroyImage(device.handle(), srcImages[i], null);
			if (srcImageMemory[i] != VK_NULL_HANDLE) vkFreeMemory(device.handle(), srcImageMemory[i], null);
			fences[i] = bitstreamBuffers[i] = bitstreamMemory[i] = VK_NULL_HANDLE;
			srcImageViews[i] = srcImages[i] = srcImageMemory[i] = VK_NULL_HANDLE;
		}
		if (dpbImageView != VK_NULL_HANDLE) vkDestroyImageView(device.handle(), dpbImageView, null);
		if (dpbImage != VK_NULL_HANDLE) vkDestroyImage(device.handle(), dpbImage, null);
		if (dpbImageMemory != VK_NULL_HANDLE) vkFreeMemory(device.handle(), dpbImageMemory, null);
		dpbImageView = dpbImage = dpbImageMemory = VK_NULL_HANDLE;
		if (queryPool != VK_NULL_HANDLE) vkDestroyQueryPool(device.handle(), queryPool, null);
		if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device.handle(), commandPool, null);
		queryPool = commandPool = VK_NULL_HANDLE;
		if (sessionParameters != VK_NULL_HANDLE)
		{
			vkDestroyVideoSessionParametersKHR(device.handle(), sessionParameters, null);
			sessionParameters = VK_NULL_HANDLE;
		}
		if (videoSession != VK_NULL_HANDLE)
		{
			vkDestroyVideoSessionKHR(device.handle(), videoSession, null);
			videoSession = VK_NULL_HANDLE;
		}
		for (long memory : sessionMemory)
		{
			if (memory != VK_NULL_HANDLE) vkFreeMemory(device.handle(), memory, null);
		}
		sessionMemory = new long[0];
	}
}
