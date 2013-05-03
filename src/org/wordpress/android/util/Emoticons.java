package org.wordpress.android.util;

import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

import android.util.Log;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES;

public class Emoticons {
    private static final boolean HAS_EMOJI=SDK_INT >= VERSION_CODES.JELLY_BEAN;
    private static final Map<String, String> wpSmilies;
    static {
        Map<String, String> smilies = new HashMap<String, String>();
        smilies.put("icon_mrgreen.gif",   HAS_EMOJI ? "😁" : ":mrgreen:" );
        smilies.put("icon_neutral.gif",   HAS_EMOJI ? "😐" : ":|" );
        smilies.put("icon_twisted.gif",   HAS_EMOJI ? "👿" : ":twisted:" );
        smilies.put("icon_arrow.gif",     HAS_EMOJI ? "➡" : ":arrow:" );
        smilies.put("icon_eek.gif",       HAS_EMOJI ? "😲" : "8-O" );
        smilies.put("icon_smile.gif",     HAS_EMOJI ? "😊" : ":)" );
        smilies.put("icon_confused.gif",  HAS_EMOJI ? "😕" : ":?" );
        smilies.put("icon_cool.gif",      HAS_EMOJI ? "😎" : "8)" );
        smilies.put("icon_evil.gif",      HAS_EMOJI ? "👿" : ":evil:" );
        smilies.put("icon_biggrin.gif",   HAS_EMOJI ? "😃" : ":D" );
        smilies.put("icon_idea.gif",      HAS_EMOJI ? "💡" : ":idea:" );
        smilies.put("icon_redface.gif",   HAS_EMOJI ? "😳" : ":oops:" );
        smilies.put("icon_razz.gif",      HAS_EMOJI ? "😜" : ":P" );
        smilies.put("icon_rolleyes.gif",  HAS_EMOJI ? "😒" : ":roll:" );
        smilies.put("icon_wink.gif",      HAS_EMOJI ? "😉" : ";)" );
        smilies.put("icon_cry.gif",       HAS_EMOJI ? "😭" : ":'(" );
        smilies.put("icon_surprised.gif", HAS_EMOJI ? "😱" : ":o" );
        smilies.put("icon_lol.gif",       HAS_EMOJI ? "😂" : ":lol:" );
        smilies.put("icon_mad.gif",       HAS_EMOJI ? "😠" : ":x" );
        smilies.put("icon_sad.gif",       HAS_EMOJI ? "😞" : ":(" );
        smilies.put("icon_exclaim.gif",   HAS_EMOJI ? "😲" : ":!:" );
        smilies.put("icon_question.gif",  HAS_EMOJI ? "😕" : ":?:" );
        
        wpSmilies = Collections.unmodifiableMap(smilies);
    }
    public static String lookupImageSmiley(String url){
        return lookupImageSmiley(url, "");
    }
    public static String lookupImageSmiley(String url, String ifNone){
        String file = url.substring(url.lastIndexOf("/") + 1);
        Log.d("Smilies", String.format("Looking for %s", file));
        if (wpSmilies.containsKey(file)) {
            return wpSmilies.get(file);
        }
        return ifNone;
    }
    
}