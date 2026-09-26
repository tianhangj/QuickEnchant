package me.tianhangj.quickenchant.task;

public abstract class OneTickTask extends LongTask {
    @Override
    public void initialize() {
        run();
    }

    @Override
    public boolean condition() {
        return false;
    }

    @Override
    public void increment() {
    }

    @Override
    public void body() {
    }

    public abstract void run();
}
