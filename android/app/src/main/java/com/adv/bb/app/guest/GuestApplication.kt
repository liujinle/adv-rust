package com.adv.bb.app.guest

import android.app.Application

/**
 * Application used when the guest manifest does not declare one.
 *
 * Plugins cast `getApplicationContext()` to `Application`; handing them a
 * ContextWrapper (the guest Context) makes them throw ClassCastException, so the
 * guest always gets a real Application instance whose base context is the guest
 * Context.
 */
internal class GuestApplication : Application()
