package kaptainwutax.seedcrackerX.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;

public class Compat {
   private static final MethodHandle CHAT_LISTENER;
   private static final MethodHandle GET_SCREEN;
   private static final MethodHandle SET_SCREEN;
   private static final MethodHandle OVERLAY_TARGET;
   private static final MethodHandle SET_OVERLAY;
   private static final MethodHandle MAIN_CAMERA;

   private static MethodHandle resolveChatListener() throws Throwable {
      Lookup lookup = MethodHandles.lookup();
      Class<?> guiClass = Class.forName("net.minecraft.client.gui.Gui");

      try {
         MethodHandle guiChat = lookup.unreflect(guiClass.getMethod("chatListener")).asType(MethodType.methodType(ChatListener.class, Object.class));
         MethodHandle guiGetter = guiGetter(lookup, guiClass);
         return MethodHandles.filterReturnValue(guiGetter, guiChat);
      } catch (NoSuchMethodException e) {
         return lookup.unreflect(Minecraft.class.getMethod("getChatListener")).asType(MethodType.methodType(ChatListener.class, Minecraft.class));
      }
   }

   private static MethodHandle resolveGetScreen() throws Throwable {
      Lookup lookup = MethodHandles.lookup();
      Class<?> guiClass = Class.forName("net.minecraft.client.gui.Gui");

      try {
         MethodHandle guiScreen = lookup.unreflect(guiClass.getMethod("screen")).asType(MethodType.methodType(Screen.class, Object.class));
         MethodHandle guiGetter = guiGetter(lookup, guiClass);
         return MethodHandles.filterReturnValue(guiGetter, guiScreen);
      } catch (NoSuchMethodException e) {
         return lookup.unreflectGetter(Minecraft.class.getField("screen")).asType(MethodType.methodType(Screen.class, Minecraft.class));
      }
   }

   private static MethodHandle resolveSetScreen() throws Throwable {
      Lookup lookup = MethodHandles.lookup();
      Class<?> guiClass = Class.forName("net.minecraft.client.gui.Gui");

      try {
         MethodHandle guiSetScreen = lookup.unreflect(guiClass.getMethod("setScreen", Screen.class))
            .asType(MethodType.methodType(void.class, Object.class, Screen.class));
         MethodHandle guiGetter = guiGetter(lookup, guiClass);
         return MethodHandles.collectArguments(guiSetScreen, 0, guiGetter);
      } catch (NoSuchMethodException e) {
         return lookup.unreflect(Minecraft.class.getMethod("setScreen", Screen.class)).asType(MethodType.methodType(void.class, Minecraft.class, Screen.class));
      }
   }

   private static Object[] resolveOverlay() throws Throwable {
      Lookup lookup = MethodHandles.lookup();
      Class<?> guiClass = Class.forName("net.minecraft.client.gui.Gui");
      MethodHandle guiGetter = guiGetter(lookup, guiClass);

      try {
         MethodHandle hudGetter = lookup.unreflectGetter(guiClass.getField("hud")).asType(MethodType.methodType(Object.class, Object.class));
         Class<?> hudClass = guiClass.getField("hud").getType();
         Method setOverlay = hudClass.getMethod("setOverlayMessage", Component.class, boolean.class);
         return new Object[]{
            MethodHandles.filterReturnValue(guiGetter, hudGetter),
            lookup.unreflect(setOverlay).asType(MethodType.methodType(void.class, Object.class, Component.class, boolean.class))
         };
      } catch (NoSuchFieldException e) {
         Method setOverlay = guiClass.getMethod("setOverlayMessage", Component.class, boolean.class);
         return new Object[]{guiGetter, lookup.unreflect(setOverlay).asType(MethodType.methodType(void.class, Object.class, Component.class, boolean.class))};
      }
   }

   private static MethodHandle resolveMainCamera() throws Throwable {
      Lookup lookup = MethodHandles.lookup();

      MethodHandle handle;
      try {
         handle = lookup.unreflect(GameRenderer.class.getMethod("mainCamera"));
      } catch (NoSuchMethodException e) {
         handle = lookup.unreflect(GameRenderer.class.getMethod("getMainCamera"));
      }

      return handle.asType(MethodType.methodType(Camera.class, GameRenderer.class));
   }

   private static MethodHandle guiGetter(Lookup lookup, Class<?> guiClass) throws Throwable {
      return lookup.unreflectGetter(Minecraft.class.getField("gui")).asType(MethodType.methodType(Object.class, Minecraft.class));
   }

   public static ChatListener chatListener(Minecraft mc) {
      try {
         return (ChatListener)CHAT_LISTENER.invokeExact((Minecraft)mc);
      } catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static void sendSystemMessage(Component message) {
      chatListener(Minecraft.getInstance()).handleSystemMessage(message, false);
   }

   public static Screen screen(Minecraft mc) {
      try {
         return (Screen)GET_SCREEN.invokeExact((Minecraft)mc);
      } catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static void setScreen(Minecraft mc, Screen screen) {
      try {
         SET_SCREEN.invokeExact((Minecraft)mc, (Screen)screen);
      } catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static void setOverlayMessage(Component message, boolean tint) {
      try {
         Object target = (Object)OVERLAY_TARGET.invokeExact((Minecraft)Minecraft.getInstance());
         SET_OVERLAY.invokeExact((Object)target, (Component)message, (boolean)tint);
      } catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static Camera mainCamera(GameRenderer gameRenderer) {
      try {
         return (Camera)MAIN_CAMERA.invokeExact((GameRenderer)gameRenderer);
      } catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   static {
      try {
         CHAT_LISTENER = resolveChatListener();
         GET_SCREEN = resolveGetScreen();
         SET_SCREEN = resolveSetScreen();
         Object[] overlay = resolveOverlay();
         OVERLAY_TARGET = (MethodHandle)overlay[0];
         SET_OVERLAY = (MethodHandle)overlay[1];
         MAIN_CAMERA = resolveMainCamera();
      } catch (Throwable t) {
         throw new ExceptionInInitializerError(t);
      }
   }
}
