package net.runelite.client.plugins.gpuvulkan;

/**
 * Packed scene vertex layout modelled after RuneLite's OpenGL GPU zone VBO.
 *
 * <p>This is deliberately separate from {@link ScenePipeline}'s 48-byte
 * compatibility layout. The stock-style zone renderer will emit vertices in
 * this format once its opaque/alpha zone passes are wired and validated.</p>
 */
final class StockSceneVertexLayout
{
	static final int VERTEX_STRIDE = 20;

	static final int OFFSET_POS = 0;       // short x/y/z, local to zone base
	static final int OFFSET_ABHSL = 8;     // [alpha:8 | bias:8 | hsl:16]
	static final int OFFSET_TEX_UV = 12;   // short texture/u/v/pad

	private StockSceneVertexLayout()
	{
	}
}
