package com.hyperbaton.cftfc.job;

import com.hyperbaton.cft.entity.ai.memory.CftMemoryModuleType;
import com.hyperbaton.cft.entity.custom.XoonglinEntity;
import com.hyperbaton.cft.job.Job;
import com.hyperbaton.cft.job.JobState;
import com.hyperbaton.cft.network.JobDisplayEntry;
import com.hyperbaton.cft.network.JobInfoData;
import com.hyperbaton.cft.network.JobStatus;
import com.hyperbaton.cft.util.JobUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A job worked a number of hours a day from a structure the Xoonglin claims. While it has hours left
 * and can work, the job sets its work memory, and its behavior does the actual work.
 */
public abstract class WorkplaceJob extends Job {

    private final double hoursPerDay;
    private final ResourceLocation requiredStructure;

    protected WorkplaceJob(Properties properties, double hoursPerDay, ResourceLocation requiredStructure) {
        super(properties);
        this.hoursPerDay = hoursPerDay;
        this.requiredStructure = requiredStructure;
    }

    public double getHoursPerDay() {
        return hoursPerDay;
    }

    @Override
    public ResourceLocation getRequiredStructureType() {
        return requiredStructure;
    }

    @Override
    public void tick(XoonglinEntity xoonglin, JobState state) {
        Level level = xoonglin.level();
        if (level.isClientSide) return;

        long dayIndex = Math.floorDiv(level.getDayTime(), 24000L);
        int neededTicks = neededTicks();

        if (state.lastDayIndex == Long.MIN_VALUE) {
            state.lastDayIndex = dayIndex;
        } else if (dayIndex != state.lastDayIndex) {
            boolean metQuota = state.workedTicksToday >= neededTicks;
            if (metQuota) {
                state.consecutiveDaysWorked++;
            } else {
                state.consecutiveDaysWorked = 0;
            }
            state.workedTicksToday = 0;
            state.lastDayIndex = dayIndex;
        }

        BlockPos structurePos = xoonglin.getAssignedStructurePos(requiredStructure);
        Brain<XoonglinEntity> brain = xoonglin.getBrain();

        // The work may take it around its workplace, so it's on duty wherever it is while working
        if (structurePos != null && brain.hasMemoryValue(getWorkMemory()) && brain.isActive(Activity.WORK)
                && canWork(xoonglin)) {
            state.workedTicksToday++;
        }

        if (structurePos == null) {
            brain.setMemory(CftMemoryModuleType.STRUCTURE_NEEDED.get(), requiredStructure);
            brain.eraseMemory(getWorkMemory());
        } else if (state.workedTicksToday < neededTicks && canWork(xoonglin)
                && JobUtil.checkWorkplace(xoonglin, this)) {
            brain.setMemory(getWorkMemory(), Boolean.TRUE);
            brain.eraseMemory(CftMemoryModuleType.STRUCTURE_NEEDED.get());
        } else {
            brain.eraseMemory(getWorkMemory());
            brain.eraseMemory(CftMemoryModuleType.STRUCTURE_NEEDED.get());
        }
    }

    @Override
    public void eraseMemories(XoonglinEntity xoonglin) {
        xoonglin.getBrain().eraseMemory(getWorkMemory());
        xoonglin.getBrain().eraseMemory(CftMemoryModuleType.STRUCTURE_NEEDED.get());
    }

    @Override
    public JobInfoData getDisplayInfo(XoonglinEntity xoonglin, JobState state) {
        int neededTicks = neededTicks();

        JobStatus status;
        if (xoonglin.getAssignedStructurePos(requiredStructure) == null) {
            status = noStructureStatus();
        } else if (!canWork(xoonglin)) {
            status = cantWorkStatus(xoonglin);
        } else if (state.workedTicksToday >= neededTicks) {
            status = JobStatus.RESTING;
        } else {
            status = JobUtil.workingStatus(xoonglin);
        }

        return new JobInfoData(status, List.of(JobDisplayEntry.progress("gui.cft.job_today",
                state.workedTicksToday, neededTicks, JobUtil.formatWorkTime(state.workedTicksToday, hoursPerDay))));
    }

    private int neededTicks() {
        return (int) Math.round(hoursPerDay * JobUtil.TICKS_PER_MC_HOUR);
    }
}
