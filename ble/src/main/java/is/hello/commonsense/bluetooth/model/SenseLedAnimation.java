package is.hello.commonsense.bluetooth.model;

import android.support.annotation.NonNull;

import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos;
import is.hello.commonsense.bluetooth.model.protobuf.SenseCommandProtos.MorpheusCommand;

/**
 * The different LED animations supported by Sense.
 *
 * @see is.hello.commonsense.bluetooth.SensePeripheral#runLedAnimation(SenseLedAnimation)
 */
public enum SenseLedAnimation {
    /**
     * Currently indicates a spinning purple glow.
     */
    BUSY(MorpheusCommand.CommandType.MORPHEUS_COMMAND_LED_BUSY),

    /**
     * Currently indicates a pulsing purple glow.
     */
    TRIPPY(MorpheusCommand.CommandType.MORPHEUS_COMMAND_LED_TRIPPY),

    /**
     * Currently indicates a solid purple flash with a fade-out.
     * <p />
     * Does not work on some production firmware versions. Prefer {@link #STOP}.
     */
    FADE_OUT(MorpheusCommand.CommandType.MORPHEUS_COMMAND_LED_OPERATION_SUCCESS),

    /**
     * Currently turns off the LEDs without a fade.
     */
    STOP(MorpheusCommand.CommandType.MORPHEUS_COMMAND_LED_OPERATION_FAILED);


    /**
     * The underlying protobuf value for the animation.
     */
    public final MorpheusCommand.CommandType commandType;

    SenseLedAnimation(@NonNull SenseCommandProtos.MorpheusCommand.CommandType commandType) {
        this.commandType = commandType;
    }
}
