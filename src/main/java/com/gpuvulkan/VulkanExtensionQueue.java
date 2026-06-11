/*
 * Copyright (c) 2026, Dennis de Vulder
 * All rights reserved.
 */
package com.gpuvulkan;

import java.util.ArrayList;
import java.util.List;

final class VulkanExtensionQueue
{
	private final List<Registration> registrations = new ArrayList<>();

	AutoCloseable register(VulkanRenderExtension extension, RenderExtensions registry)
	{
		Registration registration = new Registration(extension);
		synchronized (registrations)
		{
			registrations.add(registration);
			if (registry != null)
			{
				registration.attach(registry);
			}
		}
		return registration;
	}

	void attachQueued(RenderExtensions registry)
	{
		synchronized (registrations)
		{
			for (Registration registration : registrations)
			{
				registration.attach(registry);
			}
		}
	}

	void markBackendDetached()
	{
		synchronized (registrations)
		{
			for (Registration registration : registrations)
			{
				registration.markBackendDetached();
			}
		}
	}

	private final class Registration implements AutoCloseable
	{
		private final VulkanRenderExtension extension;
		private RenderExtensions attachedRegistry;
		private boolean closed;
		private boolean closedByBackend;

		private Registration(VulkanRenderExtension extension)
		{
			this.extension = extension;
		}

		private void attach(RenderExtensions registry)
		{
			if (closed || attachedRegistry == registry)
			{
				return;
			}
			registry.register(extension);
			attachedRegistry = registry;
			closedByBackend = false;
		}

		private void markBackendDetached()
		{
			attachedRegistry = null;
			closedByBackend = true;
		}

		@Override
		public void close()
		{
			synchronized (registrations)
			{
				if (closed)
				{
					return;
				}
				closed = true;
				registrations.remove(this);
				if (attachedRegistry != null)
				{
					attachedRegistry.unregister(extension);
					attachedRegistry = null;
				}
				else if (!closedByBackend)
				{
					extension.close();
				}
			}
		}
	}
}
