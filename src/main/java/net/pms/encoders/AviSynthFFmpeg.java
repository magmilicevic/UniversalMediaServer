/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.encoders;

import com.jgoodies.forms.builder.PanelBuilder;
import com.jgoodies.forms.factories.Borders;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.StringTokenizer;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.pms.Messages;
import net.pms.PMS;
import net.pms.dlna.DLNAMediaSubtitle;
import net.pms.dlna.DLNAResource;
import net.pms.formats.Format;
import net.pms.formats.v2.SubtitleType;
import net.pms.util.ProcessUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * This class handles the Windows-specific AviSynth/FFmpeg player combination. 
 */
public class AviSynthFFmpeg extends FFmpegVideo {
	private static final Logger LOGGER = LoggerFactory.getLogger(AviSynthFFmpeg.class);
	public static final String ID      = "avsffmpeg";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String name() {
		return "AviSynth/FFmpeg";
	}

	@Override
	public boolean avisynth() {
		return true;
	}

	@Override
	public String initialString() {
		String threads = "";
		if (PMS.getConfiguration().isFfmpegAviSynthMultithreading()) {
			threads = " -threads " + PMS.getConfiguration().getNumberOfCpuCores();
		}
		return PMS.getConfiguration().getFfmpegSettings() + " -ab " + PMS.getConfiguration().getAudioBitrate() + "k" + threads;
	}

	@Override
	public JComponent config() {
		return config("NetworkTab.5");
	}

	public static File getAVSScript(String fileName, DLNAMediaSubtitle subTrack) throws IOException {
		return getAVSScript(fileName, subTrack, -1, -1, null, null);
	}

	/*
	 * Generate the AviSynth script based on the user's settings
	 */
	public static File getAVSScript(String fileName, DLNAMediaSubtitle subTrack, int fromFrame, int toFrame, String frameRateRatio, String frameRateNumber) throws IOException {
		String onlyFileName = fileName.substring(1 + fileName.lastIndexOf("\\"));
		File file = new File(PMS.getConfiguration().getTempFolder(), "pms-avs-" + onlyFileName + ".avs");
		PrintWriter pw = new PrintWriter(new FileOutputStream(file));

		/*
		 * Prepare the framerate variables
		 */
		String numerator;
		String denominator;

		if (frameRateRatio != null && frameRateNumber != null) {
			if (frameRateRatio.equals(frameRateNumber)) {
				// No ratio was available
				numerator = frameRateRatio;
				denominator = "1";
			} else {
				String[] frameRateNumDen = frameRateRatio.split("/");
				numerator = frameRateNumDen[0];
				denominator = "1001";
			}
		} else {
			// No framerate was given so we should try the most common one
			numerator = "24000";
			denominator = "1001";
			frameRateNumber = "23.976";
		}

		String assumeFPS = ".AssumeFPS(" + numerator + "," + denominator + ")";

		String directShowFPS = "";
		if (!"0".equals(frameRateNumber)) {
			directShowFPS = ", fps=" + frameRateNumber;
		}

		String convertfps = "";
		if (PMS.getConfiguration().getFfmpegAvisynthConvertFps()) {
			//convertfps = ", convertfps=true";
		}

		File f = new File(fileName);
		if (f.exists()) {
			fileName = ProcessUtil.getShortFileNameIfWideChars(fileName);
		}

		String movieLine       = "DirectShowSource(\"" + fileName + "\"" + directShowFPS + convertfps + ")" + assumeFPS;
		String mtLine1         = "";
		String mtLine2         = "";
		String mtLine3         = "";
		String interframeLines = null;
		String interframePath  = PMS.getConfiguration().getInterFramePath();

		int Cores = 1;
		if (PMS.getConfiguration().isFfmpegAviSynthMultithreading()) {
			Cores = PMS.getConfiguration().getNumberOfCpuCores();

			// Goes at the start of the file to initiate multithreading
			mtLine1 = "SetMemoryMax(1024)\nSetMTMode(3,12)\n";

			// Goes after the input line to make multithreading more efficient
			mtLine2 = "SetMTMode(2)";
		}

		// True Motion
		if (PMS.getConfiguration().getFfmpegAvisynthInterFrame()) {
			String GPU = "";
			movieLine = movieLine + ".ConvertToYV12()";

			// Enable GPU to assist with CPU
			if (PMS.getConfiguration().getFfmpegAvisynthInterFrameGPU()){
				GPU = ", GPU=true";
			}

			interframeLines = "\n" +
				"InterFrame(Cores=4"+ GPU +",Tuning=\"Animation\")\n";
		}

		String subLine = null;
		if (subTrack != null && PMS.getConfiguration().isAutoloadSubtitles() && !PMS.getConfiguration().isMencoderDisableSubs()) {
			if (subTrack.getExternalFile() != null) {
				LOGGER.info("AviSynth script: Using subtitle track: " + subTrack);
				String function = "TextSub";
				if (subTrack.getType() == SubtitleType.VOBSUB) {
					function = "VobSub";
				}
				subLine = function + "(\"" + ProcessUtil.getShortFileNameIfWideChars(subTrack.getExternalFile().getAbsolutePath()) + "\")";
			}
		}

		ArrayList<String> lines = new ArrayList<String>();

		boolean fullyManaged = false;
		String script = PMS.getConfiguration().getAvisynthScript();
		StringTokenizer st = new StringTokenizer(script, PMS.AVS_SEPARATOR);
		while (st.hasMoreTokens()) {
			String line = st.nextToken();
			if (line.contains("<movie") || line.contains("<sub")) {
				fullyManaged = true;
			}
			lines.add(line);
		}

		if (fullyManaged) {
			for (String s : lines) {
				if (s.contains("<moviefilename>")) {
					s = s.replace("<moviefilename>", fileName);
				}

				if (movieLine != null) {
					s = s.replace("<movie>", movieLine);
				}
				s = s.replace("<sub>", subLine != null ? subLine : "#");
				pw.println(s);
			}
		} else {
			pw.println(movieLine);
			if (subLine != null) {
				pw.println(subLine);
			}
			pw.println("clip");

		}

		pw.close();
		file.deleteOnExit();
		return file;
	}

	private JCheckBox multithreading;
	private JCheckBox interframe;
	private JCheckBox interframegpu;
	private JCheckBox convertfps;

	@Override
	protected JComponent config(String languageLabel) {
		FormLayout layout = new FormLayout(
			"left:pref, 0:grow",
			"p, 3dlu, p, 3dlu, p, 3dlu, p, 3dlu, p, 3dlu");
		PanelBuilder builder = new PanelBuilder(layout);
		builder.setBorder(Borders.EMPTY_BORDER);
		builder.setOpaque(false);

		CellConstraints cc = new CellConstraints();

		JComponent cmp = builder.addSeparator(Messages.getString(languageLabel), cc.xyw(2, 1, 1));
		cmp = (JComponent) cmp.getComponent(0);
		cmp.setFont(cmp.getFont().deriveFont(Font.BOLD));

		multithreading = new JCheckBox(Messages.getString("MEncoderVideo.35"));
		multithreading.setContentAreaFilled(false);
		if (PMS.getConfiguration().isFfmpegAviSynthMultithreading()) {
			multithreading.setSelected(true);
		}
		multithreading.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				PMS.getConfiguration().setFfmpegAviSynthMultithreading(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		builder.add(multithreading, cc.xy(2, 3));

		interframe = new JCheckBox(Messages.getString("AviSynthMEncoder.13"));
		interframe.setContentAreaFilled(false);
		if (PMS.getConfiguration().getFfmpegAvisynthInterFrame()) {
			interframe.setSelected(true);
		}
		interframe.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				PMS.getConfiguration().setFfmpegAvisynthInterFrame(interframe.isSelected());
				if (PMS.getConfiguration().getFfmpegAvisynthInterFrame()) {
					JOptionPane.showMessageDialog(
						(JFrame) (SwingUtilities.getWindowAncestor((Component) PMS.get().getFrame())),
						Messages.getString("AviSynthMEncoder.16"),
						Messages.getString("Dialog.Information"),
						JOptionPane.INFORMATION_MESSAGE
					);
				}
			}
		});
		builder.add(interframe, cc.xy(2, 5));

		interframegpu = new JCheckBox(Messages.getString("AviSynthMEncoder.15"));
		interframegpu.setContentAreaFilled(false);
		if (PMS.getConfiguration().getFfmpegAvisynthInterFrameGPU()) {
			interframegpu.setSelected(true);
		}
		interframegpu.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				PMS.getConfiguration().setFfmpegAvisynthInterFrameGPU((e.getStateChange() == ItemEvent.SELECTED));
			}
		});
		builder.add(interframegpu, cc.xy(2, 7));

		convertfps = new JCheckBox(Messages.getString("AviSynthMEncoder.3"));
		convertfps.setContentAreaFilled(false);
		if (PMS.getConfiguration().getFfmpegAvisynthConvertFps()) {
			convertfps.setSelected(true);
		}
		convertfps.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				PMS.getConfiguration().setFfmpegAvisynthConvertFps((e.getStateChange() == ItemEvent.SELECTED));
			}
		});
		builder.add(convertfps, cc.xy(2, 9));

		return builder.getPanel();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean isCompatible(DLNAResource resource) {
		if (resource == null || resource.getFormat().getType() != Format.VIDEO) {
			return false;
		}

		DLNAMediaSubtitle subtitle = resource.getMediaSubtitle();

		// Check whether the subtitle actually has a language defined,
		// uninitialized DLNAMediaSubtitle objects have a null language.
		if (subtitle != null && subtitle.getLang() != null) {
			// The resource needs a subtitle, but this engine implementation does not support subtitles yet
			return false;
		}

		try {
			String audioTrackName = resource.getMediaAudio().toString();
			String defaultAudioTrackName = resource.getMedia().getAudioTracksList().get(0).toString();

			if (!audioTrackName.equals(defaultAudioTrackName)) {
				// This engine implementation only supports playback of the default audio track at this time
				return false;
			}
		} catch (NullPointerException e) {
			LOGGER.trace("AviSynth/FFmpeg cannot determine compatibility based on audio track for " + resource.getSystemName());
		} catch (IndexOutOfBoundsException e) {
			LOGGER.trace("AviSynth/FFmpeg cannot determine compatibility based on default audio track for " + resource.getSystemName());
		}

		Format format = resource.getFormat();

		if (format != null) {
			Format.Identifier id = format.getIdentifier();

			if (id.equals(Format.Identifier.MKV) || id.equals(Format.Identifier.MPG)) {
				return true;
			}
		}

		return false;
	}
}
