/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import java.nio.ByteBuffer;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK13.*;

final class SceneDrawEmitter
{
	private final DrawCallbackStats stats;
	private final boolean repushConstantsEveryDraw;
	private boolean recordStats;

	SceneDrawEmitter(DrawCallbackStats stats, boolean repushConstantsEveryDraw)
	{
		this.stats = stats;
		this.repushConstantsEveryDraw = repushConstantsEveryDraw;
	}

	void beginFrame(boolean recordStats)
	{
		this.recordStats = recordStats;
	}

	void drawRange(VkCommandBuffer cmd, int start, int end, int[] skips, int pairCount,
		boolean applySkips, int slotFirstVertex,
		long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		if (end <= start)
		{
			return;
		}
		if (applySkips && pairCount > 0)
		{
			drawWithSkips(cmd, start, end, skips, pairCount, slotFirstVertex,
				pipelineLayout, vertPush, fragPush);
			return;
		}

		pushForDraw(cmd, pipelineLayout, vertPush, fragPush);
		vkCmdDraw(cmd, end - start, 1, slotFirstVertex + start, 0);
		recordDrawCall(end - start);
	}

	void pushConstants(VkCommandBuffer cmd, long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, vertPush);
		vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT,
			ScenePipeline.VERT_PUSH_BYTES, fragPush);
		if (recordStats)
		{
			stats.scenePushConstants.addAndGet(2);
		}
	}

	private void drawWithSkips(VkCommandBuffer cmd, int start, int end, int[] skips, int pairCount,
		int slotFirstVertex, long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		int cursor = start;
		for (int k = 0; k < pairCount; k++)
		{
			int s = skips[k * 2];
			int e = skips[k * 2 + 1];
			if (e <= cursor)
			{
				continue;
			}
			if (s > cursor)
			{
				int n = Math.min(s, end) - cursor;
				if (n > 0)
				{
					pushForDraw(cmd, pipelineLayout, vertPush, fragPush);
					vkCmdDraw(cmd, n, 1, slotFirstVertex + cursor, 0);
					recordDrawCall(n);
				}
			}
			cursor = Math.max(cursor, e);
			if (cursor >= end)
			{
				return;
			}
		}
		if (cursor < end)
		{
			pushForDraw(cmd, pipelineLayout, vertPush, fragPush);
			vkCmdDraw(cmd, end - cursor, 1, slotFirstVertex + cursor, 0);
			recordDrawCall(end - cursor);
		}
	}

	private void pushForDraw(VkCommandBuffer cmd, long pipelineLayout, ByteBuffer vertPush, ByteBuffer fragPush)
	{
		if (repushConstantsEveryDraw)
		{
			pushConstants(cmd, pipelineLayout, vertPush, fragPush);
		}
	}

	private void recordDrawCall(int vertices)
	{
		if (!recordStats)
		{
			return;
		}
		stats.sceneDrawCalls.incrementAndGet();
		stats.sceneDrawVertices.addAndGet(vertices);
	}
}
