package fr.citizen.voice.svc;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

/**
 * Plugin Simple Voice Chat (détecté automatiquement par voicechat grâce à l'annotation).
 * Cette classe n'est chargée que si Simple Voice Chat est installé sur le serveur.
 */
@ForgeVoicechatPlugin
public final class SvcPlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return "citizenvoice";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> SvcBridge.start(e.getVoicechat()));
        registration.registerEvent(MicrophonePacketEvent.class, SvcBridge::onMicrophone);
    }
}
