package com.oasisfeng.island.shuttle

import android.app.*
import android.app.Activity.RESULT_FIRST_USER
import android.app.PendingIntent.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_RECEIVER_FOREGROUND
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.os.Build.VERSION_CODES.O
import android.util.Log
import android.view.*
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.oasisfeng.android.os.UserHandles
import com.oasisfeng.island.engine.CrossProfile
import com.oasisfeng.island.util.*
import kotlinx.coroutines.*
import java.io.Serializable
import java.lang.reflect.Modifier
import kotlin.coroutines.*
import kotlin.random.Random

private typealias CtxFun<R> = Context.() -> R

class PendingIntentShuttle: BroadcastReceiver() {

	class ProfileUnlockCanceledException(message: String) : CancellationException(message)

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action.let { it == Intent.ACTION_LOCKED_BOOT_COMPLETED || it == Intent.ACTION_MY_PACKAGE_REPLACED}) {
			Log.d(TAG, "$prefix Initiated by $intent")
			if (Users.isOwner()) sendToAllUnlockedProfiles(context)
			else sendToParentProfileByActivityIfNotYet(context)
			return }    // We cannot initiate shuttle sending from to owner user on Android 8+, due to visibility restriction.
		if (intent.getLongExtra(ActivityOptions.EXTRA_USAGE_TIME_REPORT, -1) >= 0) {
			intent.getParcelableExtra<Bundle>(ActivityOptions.EXTRA_USAGE_TIME_REPORT_PACKAGES)?.apply {
				Log.d(TAG, "Callee report: ${keySet().joinToString()}") }
			return
		}

		when(val payload = intent.getParcelableExtra<Parcelable>(null)) {
			is Closure -> {
				Log.d(TAG, "$prefix Invoke closure from ${payload.userId}")
				try { invokeClosureAndSendResult(context, payload) { code, extras -> setResult(code, null, extras) }}
				catch (t: Throwable) { Log.w(TAG, "$prefix Error invoking $payload", t) }}
			is PendingIntent -> {
				require(payload.creatorPackage == context.packageName)
				save(context, payload) }
			else -> Log.e(TAG, "$prefix Invalid payload type: ${payload.javaClass}") }
	}

	companion object {

		private fun invokeClosureAndSendResult(context: Context, closure: Closure, sendResult: ((Int, Bundle) -> Unit)?) {
			try {
				val result = closure.invoke(context)

				sendResult?.invoke(Activity.RESULT_OK, Bundle().apply { @Suppress("UNCHECKED_CAST") when (result) {
					null, is Unit -> putString(null, null)
					is Serializable -> putSerializable(null, result)   // Catch most types, which are just put into the underling map.
					is Parcelable -> putParcelable(null, result)
					is CharSequence -> putCharSequence(null, result)   // CharSequence may not be serializable
					else -> throw UnsupportedOperationException("Return type is not yet supported: ${result.javaClass}") }}) }
			catch (e: Throwable) {
				sendResult?.invoke(RESULT_FIRST_USER, Bundle().apply { putSerializable(null, e) })
						?: Log.e(TAG, "$prefix Error executing $closure") }
		}

		private fun <R> buildInvocation(procedure: CtxFun<R>, resultReceiver: ResultReceiver)
				= Invocation(Closure(procedure), resultReceiver)

		private fun <T> Class<T>.getMemberFields()
				= declaredFields.filter { if (Modifier.isStatic(it.modifiers)) false else { it.isAccessible = true; true }}

		private fun wrapIfNeeded(obj: Any?): Any? = if (obj is Context) null else obj

		fun isReady(context: Context, profile: UserHandle) = getLocker(context, profile) != null

		internal suspend fun <R> shuttle(context: Context, shuttle: PendingIntent, procedure: CtxFun<R>): R = suspendCoroutine { continuation ->
			try { shuttle.send(context, Activity.RESULT_CANCELED,
					Intent().putExtra(null, Closure(procedure)).addFlags(FLAG_RECEIVER_FOREGROUND),
					{ _,_, result,_, extras -> continuation.resume(result, extras, procedure.javaClass) }, null) }
			catch (e: RuntimeException) { continuation.resumeWithException(e) }}

		private fun <R> Continuation<R>.resume(result: Int, extras: Bundle?, procedureClass: Class<*>? = null) = when (result) {
			Activity.RESULT_OK  -> resume(@Suppress("UNCHECKED_CAST") (extras?.get(null) as R))
			RESULT_FIRST_USER   -> resumeWithException(extras?.get(null) as Throwable)
			else                -> resumeWithException(RuntimeException("Error shuttling ${procedureClass ?: ""}")) }

		@ProfileUser fun sendToParentProfileByActivityIfNotYet(context: Context) {
			if (mSentByActivity) return     // Activity start is relatively heavy and cross-profile toast will be shown.
			try {
				val intent = Intent(ACTION_SHUTTLE).putExtra(null, buildReverseShuttle(context))
				CrossProfile(context).startActivityInParentProfile(intent.addFlags(SILENT_LAUNCH_FLAGS))
				mSentByActivity = true
			} catch (e: RuntimeException) { Log.e(TAG, "Error establishing shuttle to parent profile.", e) }
		}
		private var mSentByActivity: Boolean = false

		@OwnerUser fun sendToAllUnlockedProfiles(context: Context) {
			check(Users.isOwner()) { "Must be called in owner user" }
			context.getSystemService(UserManager::class.java)!!.userProfiles.forEach {
				if (it != Users.current()) sendToProfileIfUnlocked(context, it) }
		}

		@OwnerUser private fun sendToProfileIfUnlocked(context: Context, profile: UserHandle) = GlobalScope.launch {
			val shuttle = load(context, profile)
			if (shuttle != null) try {
				return@launch shuttle.send(context, 0, Intent().putExtra(null, buildReverseShuttle(context))) }
			catch (e: CanceledException) {
				Log.w(TAG, "Old shuttle (${Users.current().toId()} to ${profile.toId()}) is broken, rebuild now.") }

			if (context.getSystemService(UserManager::class.java)!!.isUserUnlocked(profile))
				sendToProfileByActivity(context, profile)
			else Log.i(TAG, "$prefix Skip stopped or locked profile: ${profile.toId()}")
		}

		@OwnerUser internal suspend fun <R> sendToProfileAndShuttle(context: Context, profile: UserHandle, procedure: CtxFun<R>): R? {
			val userManager = context.getSystemService<UserManager>()!!
			if (! userManager.isUserUnlocked(profile)) {  // ActivityOptions cannot be passed through if profile is pending unlock.
				val randomAction = "com.oasisfeng.island.action." + Random.nextInt(10000)
				val pi = getBroadcast(context, 0, Intent(randomAction).addFlags(FLAG_RECEIVER_FOREGROUND), FLAG_ONE_SHOT)
				startMainActivityInProfile(context, profile, ActivityOptions.makeBasic().apply { requestUsageTimeReport(pi) }.toBundle())

				suspendCoroutine<Intent> { continuation ->
					context.registerReceiver(object: BroadcastReceiver() { override fun onReceive(c: Context, intent: Intent) {
						context.unregisterReceiver(this)
						continuation.resume(intent)
					}}, IntentFilter(randomAction)) }

				// Probably due to timing issue, the profile may be "running" but still NOT "unlocked".
				if (! userManager.isUserUnlocked(profile) && ! userManager.isUserRunning(profile)) {
					coroutineContext.cancel(ProfileUnlockCanceledException("Profile unlock is canceled by user: $profile"))
					return null }
				Log.d(TAG, "Profile unlocked by user explicitly: $profile") }

			return suspendCoroutine<R> { continuation ->
				val resultReceiver = object : ResultReceiver(null) { override fun onReceiveResult(code: Int, data: Bundle?) {
					continuation.resume(code, data) }}
				GlobalScope.launch(Dispatchers.Main.immediate) {
					sendToProfileByActivity(context, profile, buildInvocation(procedure, resultReceiver)) }}
		}

		@OwnerUser @MainThread private suspend fun sendToProfileByActivity(context: Context, profile: UserHandle, payload: Parcelable? = null)
				= startMainActivityInProfile(context, profile, buildActivityOptionsWithReverseShuttle(context, payload))

		private fun startMainActivityInProfile(context: Context, profile: UserHandle, activityOptions: Bundle?): Boolean {
			val la = context.getSystemService(LauncherApps::class.java)!!
			la.getActivityList(context.packageName, profile).getOrNull(0)?.also {
				la.startMainActivity(it.componentName, profile, null, activityOptions)
				Log.i(TAG, "$prefix Establishing shuttle to profile ${profile.toId()}...")
				return true } ?: Log.e(TAG, "No launcher activity in profile ${profile.toId()}")
			return false
		}

		private fun buildReverseShuttle(context: Context): PendingIntent    // For use by other profile to shuttle back
				= getBroadcast(context, Users.current().toId(),
				Intent(context, PendingIntentShuttle::class.java), FLAG_UPDATE_CURRENT)

		@MainThread private suspend fun buildActivityOptionsWithReverseShuttle(context: Context, payload: Parcelable?): Bundle = withContext(Dispatchers.Main.immediate) {
				ActivityOptions.makeSceneTransitionAnimation(DummyActivity(context), View(context), "")
				.apply { requestUsageTimeReport(buildReverseShuttle(context)); }.toBundle()
				.apply { if (payload != null) putParcelable(KEY_RESULT_DATA, Intent().putExtra(null, payload)) }}

		@JvmStatic fun collectFromActivity(activity: Activity): Boolean {
			if (! collect(activity)) return false
			return true
		}

		private fun collect(activity: Activity): Boolean {
			if (Users.isOwner()) activity.intent.getParcelableExtra<PendingIntent>(null)?.also { shuttle ->
				return true.also { saveAndReply(activity, shuttle) }}   // Payload can be carried to parent profile directly in Intent.
			val bundle = Hacks.Activity_getActivityOptions?.invoke()?.on(activity)?.toBundle()
			if (bundle == null) {
				if (activity.callingActivity ?: CallerAwareActivity.getCallingPackage(activity) == Modules.MODULE_ENGINE)
					return true.also { Log.w(TAG, "Abort caller due to possible shuttle interruption") }
				return false.also { Log.v(TAG, "No options to inspect (referrer: ${activity.referrer}, activity: ${activity.callingActivity}, package: ${activity.callingPackage})") }
			}
			val shuttle = bundle.getParcelable<PendingIntent>(KEY_USAGE_TIME_REPORT)
					?: return false.also { Log.d(TAG, "No shuttle to collect") }
			if (UserHandles.getAppId(shuttle.creatorUid) != UserHandles.getAppId(Process.myUid()))
				return false.also { Log.d(TAG, "Not a shuttle (created by ${shuttle.creatorPackage})") } // Not from us
			Log.i(TAG, "$prefix Shuttle collected")

			saveAndReply(activity, shuttle)

			bundle.getParcelable<Intent>(KEY_RESULT_DATA)?.apply {
				setExtrasClassLoader(Invocation::class.java.classLoader)    // To avoid "BadParcelableException: ClassNotFoundException when unmarshalling"
			}?.getParcelableExtra<Invocation>(null)?.also { invocation ->
				Log.i(TAG, "$prefix Closure collected")
				invokeClosureAndSendResult(activity, invocation.closure, invocation.resultReceiver::send) }
			return true
		}

		private fun saveAndReply(context: Context, shuttle: PendingIntent) {
			save(context, shuttle)
			Log.d(TAG, "$prefix Reply to ${UserHandles.getUserId(shuttle.creatorUid)}")
			shuttle.send(context, 0, Intent().putExtra(null, buildReverseShuttle(context)))
		}

		/** Shuttle cannot be retrieved directly as its "user" is not current, so we wrap it within a local "locker" PendingIntent.*/
		private fun save(context: Context, shuttle: PendingIntent) {
			val user = shuttle.creatorUserHandle?.takeIf { it != Users.current() }?.toId()
					?: return Unit.also { Log.e(TAG, "$prefix Not a shuttle: $shuttle") }
			Log.d(TAG, "$prefix Received shuttle from $user")
			val pack = Intent(ACTION_SHUTTLE_LOCKER).setPackage("").putExtra(null, shuttle)
			val locker = getBroadcast(context, user, pack, FLAG_UPDATE_CURRENT)
			context.getSystemService(AlarmManager::class.java)!!.apply {    // To keep it in memory after process death.
				cancel(locker)
				set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 365 * 24 * 3600_000L, locker) }
		}

		internal suspend fun load(context: Context, profile: UserHandle): PendingIntent? {
			require(profile != Users.current()) { "Same profile: $profile" }
			val locker = getLocker(context, profile) ?: return null
			return suspendCoroutine { continuation -> locker.send(context, 0, null, { _, intent, _, _, _ ->
				continuation.resume(intent.getParcelableExtra(null)) }, null) }
		}

		private fun getLocker(context: Context, profile: UserHandle)
				= getBroadcast(context, profile.toId(), Intent(ACTION_SHUTTLE_LOCKER).setPackage(""), FLAG_NO_CREATE)

		private val prefix = "[" + Users.current().toId() + "]"

		private const val ACTION_SHUTTLE = "com.oasisfeng.island.action.SHUTTLE"        // For ReceiverActivity
		private const val ACTION_SHUTTLE_LOCKER = "SHUTTLE_LOCKER"
		private const val KEY_USAGE_TIME_REPORT = "android:activity.usageTimeReport"    // From ActivityOptions
		private const val KEY_RESULT_DATA = "android:activity.resultData"               // From ActivityOptions
		private const val SILENT_LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
				Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION or
				Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY

		// Automatically generated fields for captured variables, by compiler (indeterminate order)
		private fun extractVariablesFromFields(procedure: CtxFun<*>)
				= procedure.javaClass.getMemberFields().map { wrapIfNeeded(it.get(procedure)) }.toTypedArray()
	}

	open class Closure(private val functionClass: Class<CtxFun<*>>, private val variables: Array<Any?>,
	                   val userId: Int = Users.current().toId()): Parcelable {

		internal fun invoke(context: Context): Any? {
			val constructor = functionClass.declaredConstructors[0].apply { isAccessible = true }
			val args: Array<Any?> = constructor.parameterTypes.map(::getDefaultValue).toTypedArray()
			val variables = variables
			@Suppress("UNCHECKED_CAST") val block = constructor.newInstance(* args) as CtxFun<*>
			block.javaClass.getMemberFields().forEachIndexed { index, field ->  // Constructor arguments do not matter, as all fields are replaced.
				field.set(block, when (field.type) {
					Context::class.java -> context
					Closure::class.java -> (variables[index] as? Closure)?.invoke(context)
					else -> variables[index] }) }
			return block(context)
		}

		constructor(procedure: CtxFun<*>): this(procedure.javaClass, extractVariablesFromFields(procedure)) {
			val constructors = javaClass.declaredConstructors
			require(constructors.isNotEmpty()) { "The method must have at least one constructor" }

		}

		private fun getDefaultValue(type: Class<*>)
				= if (type.isPrimitive) java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(type, 1), 0) else null

		override fun toString() = "Closure {${functionClass.name}}"

		@Suppress("UNCHECKED_CAST") constructor(parcel: Parcel, cl: ClassLoader)
				: this(cl.loadClass(parcel.readString()) as Class<CtxFun<*>>, parcel.readArray(cl)!!, parcel.readInt())
		override fun writeToParcel(dest: Parcel, flags: Int)
				= dest.run { writeString(functionClass.name); writeArray(variables); writeInt(userId) }
		override fun describeContents() = 0

		companion object CREATOR : Parcelable.ClassLoaderCreator<Closure> {
			override fun createFromParcel(parcel: Parcel, classLoader: ClassLoader) = Closure(parcel, classLoader)
			override fun createFromParcel(parcel: Parcel) = Closure(parcel, Closure::class.java.classLoader!!)
			override fun newArray(size: Int): Array<Closure?> = arrayOfNulls(size)
		}
	}

	class Invocation: Parcelable {

		internal val closure: Closure
		internal val resultReceiver: ResultReceiver

		constructor(closure: Closure, resultReceiver: ResultReceiver) {
			this.closure = closure
			this.resultReceiver = resultReceiver
		}

		constructor(parcel: Parcel, classLoader: ClassLoader) {
			closure = Closure.createFromParcel(parcel, classLoader)
			resultReceiver = ResultReceiver.CREATOR.createFromParcel(parcel)
		}
		override fun writeToParcel(dest: Parcel, flags: Int) {
			closure.writeToParcel(dest, 0)
			resultReceiver.writeToParcel(dest, 0)
		}
		override fun describeContents() = 0

		companion object CREATOR : Parcelable.ClassLoaderCreator<Invocation> {
			override fun createFromParcel(parcel: Parcel, classLoader: ClassLoader) = Invocation(parcel, classLoader)
			override fun createFromParcel(parcel: Parcel) = Invocation(parcel, Invocation::class.java.classLoader!!)
			override fun newArray(size: Int): Array<Invocation?> = arrayOfNulls(size)
		}
	}

	@RequiresApi(O) class Initializer: Service() {  // Only supported on Android O+ (see IslandPersistentService in module "engine")

		override fun onCreate() {
			if (Users.isOwner()) sendToAllUnlockedProfiles(this)
			else sendToParentProfileByActivityIfNotYet(this)
		}

		override fun onBind(intent: Intent?): IBinder? = null   // Return null to stop running
	}

	class ReceiverActivity: Activity() {

		override fun onCreate(savedInstanceState: Bundle?) {
			super.onCreate(savedInstanceState)
			finish()
			collect(this)
		}
	}

	private class DummyActivity(context: Context): Activity() {
		override fun getWindow() = mDummyWindow
		private val mDummyWindow = DummyWindow(context)
	}
	private class DummyWindow(context: Context): Window(context) {
		init { requestFeature(FEATURE_ACTIVITY_TRANSITIONS) }   // Required for ANIM_SCENE_TRANSITION. See ActivityOptions.makeSceneTransitionAnimation().
		override fun superDispatchTrackballEvent(event: MotionEvent?) = false
		override fun setNavigationBarColor(color: Int) {}
		override fun onConfigurationChanged(newConfig: Configuration?) {}
		override fun peekDecorView() = null
		override fun setFeatureDrawableUri(featureId: Int, uri: Uri?) {}
		override fun setVolumeControlStream(streamType: Int) {}
		override fun setBackgroundDrawable(drawable: Drawable?) {}
		override fun takeKeyEvents(get: Boolean) {}
		override fun getNavigationBarColor() = 0
		override fun superDispatchGenericMotionEvent(event: MotionEvent?) = false
		override fun superDispatchKeyEvent(event: KeyEvent?) = false
		override fun getLayoutInflater(): LayoutInflater = context.getSystemService(LayoutInflater::class.java)!!
		override fun performContextMenuIdentifierAction(id: Int, flags: Int) = false
		override fun setStatusBarColor(color: Int) {}
		override fun togglePanel(featureId: Int, event: KeyEvent?) {}
		override fun performPanelIdentifierAction(featureId: Int, id: Int, flags: Int) = false
		override fun closeAllPanels() {}
		override fun superDispatchKeyShortcutEvent(event: KeyEvent?) = false
		override fun superDispatchTouchEvent(event: MotionEvent?) = false
		override fun setDecorCaptionShade(decorCaptionShade: Int) {}
		override fun takeInputQueue(callback: InputQueue.Callback?) {}
		override fun setResizingCaptionDrawable(drawable: Drawable?) {}
		override fun performPanelShortcut(featureId: Int, keyCode: Int, event: KeyEvent?, flags: Int) = false
		override fun setFeatureDrawable(featureId: Int, drawable: Drawable?) {}
		override fun saveHierarchyState() = null
		override fun addContentView(view: View?, params: ViewGroup.LayoutParams?) {}
		override fun invalidatePanelMenu(featureId: Int) {}
		override fun setTitle(title: CharSequence?) {}
		override fun setChildDrawable(featureId: Int, drawable: Drawable?) {}
		override fun closePanel(featureId: Int) {}
		override fun restoreHierarchyState(savedInstanceState: Bundle?) {}
		override fun onActive() {}
		override fun getDecorView(): View { TODO("Not yet implemented") }
		override fun setTitleColor(textColor: Int) {}
		override fun setContentView(layoutResID: Int) {}
		override fun setContentView(view: View?) {}
		override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {}
		override fun getVolumeControlStream() = AudioManager.USE_DEFAULT_STREAM_TYPE
		override fun getCurrentFocus(): View? = null
		override fun getStatusBarColor() = 0
		override fun isShortcutKey(keyCode: Int, event: KeyEvent?) = false
		override fun setFeatureDrawableAlpha(featureId: Int, alpha: Int) {}
		override fun isFloating() = false
		override fun setFeatureDrawableResource(featureId: Int, resId: Int) {}
		override fun setFeatureInt(featureId: Int, value: Int) {}
		override fun setChildInt(featureId: Int, value: Int) {}
		override fun takeSurface(callback: SurfaceHolder.Callback2?) {}
		override fun openPanel(featureId: Int, event: KeyEvent?) {}
	}
}

private const val TAG = "Island.PIS"
